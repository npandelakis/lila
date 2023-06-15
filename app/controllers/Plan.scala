package controllers

import java.util.Currency
import play.api.libs.json.*
import play.api.mvc.*

import lila.api.WebContext
import lila.app.{ given, * }
import lila.common.EmailAddress
import lila.plan.{
  CreateStripeSession,
  Freq,
  Money,
  CustomerInfo,
  NextUrls,
  PayPalOrderId,
  PayPalSubscription,
  PayPalSubscriptionId,
  PlanCheckout,
  StripeCustomer,
  StripeCustomerId
}
import lila.user.{ User as UserModel }
import views.*

final class Plan(env: Env) extends LilaController(env):

  private val logger = lila.log("plan")

  def index = Open:
    pageHit
    ctx.me.fold(indexAnon): me =>
      import lila.plan.PlanApi.SyncResult.*
      env.plan.api.sync(me) flatMap {
        case ReloadUser => Redirect(routes.Plan.index)
        case Synced(Some(patron), None, None) =>
          env.user.repo email me.id flatMap { email =>
            renderIndex(email, patron.some)
          }
        case Synced(Some(patron), Some(stripeCus), _) => indexStripePatron(me, patron, stripeCus)
        case Synced(Some(patron), _, Some(payPalSub)) => indexPayPalPatron(me, patron, payPalSub)
        case _                                        => indexFreeUser(me)
      }

  def list = Open:
    ctx.me.fold(Redirect(routes.Plan.index).toFuccess): me =>
      import lila.plan.PlanApi.SyncResult.*
      env.plan.api.sync(me) flatMap {
        case ReloadUser            => Redirect(routes.Plan.list)
        case Synced(Some(_), _, _) => indexFreeUser(me)
        case _                     => Redirect(routes.Plan.index)
      }

  private def indexAnon(using WebContext) = renderIndex(email = none, patron = none)

  private def indexFreeUser(me: UserModel)(using WebContext) =
    env.user.repo email me.id flatMap { renderIndex(_, patron = none) }

  private def renderIndex(email: Option[EmailAddress], patron: Option[lila.plan.Patron])(using
      WebContext
  ): Fu[Result] =
    for
      recentIds <- env.plan.api.recentChargeUserIds
      bestIds   <- env.plan.api.topPatronUserIds
      _         <- env.user.lightUserApi.preloadMany(recentIds ::: bestIds)
      pricing   <- env.plan.priceApi.pricingOrDefault(myCurrency)
    yield Ok(
      html.plan.index(
        stripePublicKey = env.plan.stripePublicKey,
        payPalPublicKey = env.plan.payPalPublicKey,
        email = email,
        patron = patron,
        recentIds = recentIds,
        bestIds = bestIds,
        pricing = pricing
      )
    )

  private def indexStripePatron(me: UserModel, patron: lila.plan.Patron, customer: StripeCustomer)(using
      ctx: WebContext
  ) = for {
    pricing <- env.plan.priceApi.pricingOrDefault(myCurrency)
    info    <- env.plan.api.stripe.customerInfo(me, customer)
    gifts   <- env.plan.api.giftsFrom(me)
    res <- info match
      case Some(info: CustomerInfo.Monthly) =>
        Ok(html.plan.indexStripe(me, patron, info, env.plan.stripePublicKey, pricing, gifts)).toFuccess
      case Some(CustomerInfo.OneTime(cus)) =>
        renderIndex(cus.email map { EmailAddress(_) }, patron.some)
      case None =>
        env.user.repo email me.id flatMap {
          renderIndex(_, patron.some)
        }
  } yield res

  private def indexPayPalPatron(me: UserModel, patron: lila.plan.Patron, sub: PayPalSubscription)(using
      WebContext
  ) =
    env.plan.api.giftsFrom(me) map { gifts =>
      Ok(html.plan.indexPayPal(me, patron, sub, gifts))
    }

  private def myCurrency(using ctx: WebContext): Currency =
    get("currency") flatMap lila.plan.CurrencyApi.currencyOption getOrElse
      env.plan.currencyApi.currencyByCountryCodeOrLang(
        env.security.geoIP(ctx.ip).flatMap(_.countryCode),
        ctx.lang
      )

  def features = Open:
    pageHit
    html.plan.features()

  def switch = AuthBody { ctx ?=> me =>
    env.plan.priceApi.pricingOrDefault(myCurrency) flatMap { pricing =>
      lila.plan.Switch
        .form(pricing)
        .bindFromRequest()
        .fold(
          _ => funit,
          data => env.plan.api.switch(me, data.money)
        ) inject Redirect(routes.Plan.index)
    }
  }

  def cancel = AuthBody { _ ?=> me =>
    env.plan.api.cancel(me) inject Redirect(routes.Plan.index)
  }

  def thanks = Open:
    // wait for the payment data from stripe or paypal
    lila.common.LilaFuture.delay(2.seconds):
      for
        patron   <- ctx.me so env.plan.api.userPatron
        customer <- patron so env.plan.api.stripe.patronCustomer
        gift     <- ctx.me so env.plan.api.recentGiftFrom
      yield Ok(html.plan.thanks(patron, customer, gift))

  def webhook = AnonBodyOf(parse.json): body =>
    if req.headers.hasHeader("PAYPAL-TRANSMISSION-SIG")
    then env.plan.webhook.payPal(body) inject Ok("kthxbye")
    else env.plan.webhook.stripe(body) inject Ok("kthxbye")

  import lila.plan.StripeClient.{ StripeException, CantUseException }
  def badStripeApiCall: PartialFunction[Throwable, Result] = {
    case e @ CantUseException => BadRequest(jsonError(e.getMessage))
    case e: StripeException =>
      logger.error("Plan.stripeCheckout", e)
      BadRequest(jsonError("Stripe API call failed"))
  }

  private def createStripeSession(
      me: UserModel,
      checkout: PlanCheckout,
      customerId: StripeCustomerId,
      giftTo: Option[UserModel]
  )(using ctx: WebContext) = {
    for
      isLifetime <- env.plan.priceApi.isLifetime(checkout.money)
      data = CreateStripeSession(
        customerId,
        checkout,
        NextUrls(
          cancel = s"${env.net.baseUrl}${routes.Plan.index}",
          success = s"${env.net.baseUrl}${routes.Plan.thanks}"
        ),
        giftTo = giftTo,
        isLifetime = isLifetime,
        ip = ctx.ip
      )
      session <- env.plan.api.stripe.createSession(data, me)
    yield JsonOk(Json.obj("session" -> Json.obj("id" -> session.id.value)))
  }.recover(badStripeApiCall)

  def switchStripePlan(user: UserModel, money: Money) =
    env.plan.api
      .switch(user, money)
      .inject(jsonOkResult)
      .recover(badStripeApiCall)

  private val CheckoutRateLimit = lila.memo.RateLimit.composite[lila.common.IpAddress](
    key = "plan.checkout.ip"
  )(
    ("fast", 8, 10.minute),
    ("slow", 40, 1.day)
  )

  private val CaptureRateLimit = lila.memo.RateLimit.composite[lila.common.IpAddress](
    key = "plan.capture.ip"
  )(
    ("fast", 8, 10.minute),
    ("slow", 40, 1.day)
  )

  def stripeCheckout = AuthBody { ctx ?=> me =>
    CheckoutRateLimit(ctx.ip, rateLimitedFu):
      env.plan.priceApi
        .pricingOrDefault(myCurrency)
        .flatMap: pricing =>
          env.plan.checkoutForm
            .form(pricing)
            .bindFromRequest()
            .fold(
              err =>
                logger.info(s"Plan.stripeCheckout 400: $err")
                BadRequest(jsonError(err.errors.map(_.message) mkString ", "))
              ,
              data =>
                val checkout = data.fixFreq
                for
                  gifted   <- checkout.giftTo.filterNot(ctx.userId.has).so(env.user.repo.enabledById)
                  customer <- env.plan.api.stripe.userCustomer(me)
                  session <- customer match
                    case Some(customer) if checkout.freq == Freq.Onetime =>
                      createStripeSession(me, checkout, customer.id, gifted)
                    case Some(customer) if customer.firstSubscription.isDefined =>
                      switchStripePlan(me, checkout.money)
                    case _ =>
                      env.plan.api.stripe
                        .makeCustomer(me, checkout)
                        .flatMap(customer => createStripeSession(me, checkout, customer.id, gifted))
                yield session
            )
  }

  def updatePayment = AuthBody { ctx ?=> me =>
    CaptureRateLimit(ctx.ip, rateLimitedFu):
      env.plan.api.stripe.userCustomer(me) flatMap {
        _.flatMap(_.firstSubscription).map(_.copy(ip = ctx.ip.some)) so { sub =>
          env.plan.api.stripe
            .createPaymentUpdateSession(
              sub,
              NextUrls(
                cancel = s"${env.net.baseUrl}${routes.Plan.index}",
                success =
                  s"${env.net.baseUrl}${routes.Plan.updatePaymentCallback}?session={CHECKOUT_SESSION_ID}"
              )
            )
            .map(session => JsonOk(Json.obj("session" -> Json.obj("id" -> session.id.value))))
            .recover(badStripeApiCall)
        }
      }
  }

  def updatePaymentCallback = AuthBody { ctx ?=> me =>
    get("session") so { session =>
      env.plan.api.stripe.userCustomer(me) flatMap {
        _.flatMap(_.firstSubscription) so { sub =>
          env.plan.api.stripe.updatePaymentMethod(sub, session) inject Redirect(routes.Plan.index)
        }
      }
    }
  }

  def payPalCheckout = AuthBody { ctx ?=> me =>
    CheckoutRateLimit(ctx.ip, rateLimitedFu):
      env.plan.priceApi.pricingOrDefault(myCurrency) flatMap { pricing =>
        env.plan.checkoutForm
          .form(pricing)
          .bindFromRequest()
          .fold(
            err =>
              logger.info(s"Plan.payPalCheckout 400: $err")
              BadRequest(jsonError(err.errors.map(_.message) mkString ", "))
            ,
            data =>
              val checkout = data.fixFreq
              if checkout.freq.renew then
                env.plan.api.payPal
                  .createSubscription(checkout, me)
                  .map: sub =>
                    JsonOk(Json.obj("subscription" -> Json.obj("id" -> sub.id.value)))
              else
                for
                  gifted <- checkout.giftTo.filterNot(ctx.userId.has).so(env.user.repo.enabledById)
                  // customer <- env.plan.api.userCustomer(me)
                  order <- env.plan.api.payPal.createOrder(checkout, me, gifted)
                yield JsonOk(Json.obj("order" -> Json.obj("id" -> order.id.value)))
          )
      }
  }

  def payPalCapture(orderId: String) = Auth { ctx ?=> me =>
    CaptureRateLimit(ctx.ip, rateLimitedFu):
      get("sub")
        .map(PayPalSubscriptionId.apply)
        .match
          case None => env.plan.api.payPal.captureOrder(PayPalOrderId(orderId), ctx.ip)
          case Some(subId) =>
            env.plan.api.payPal.captureSubscription(PayPalOrderId(orderId), subId, me, ctx.ip)
        .inject(jsonOkResult)
  }

  // deprecated
  def payPalIpn = AnonBody:
    lila.plan.PlanForm.ipn
      .bindFromRequest()
      .fold(
        err =>
          if err.errors("txn_type").nonEmpty then
            logger.debug(s"Plan.payPalIpn ignore txn_type = ${err.data get "txn_type"}")
            Ok
          else
            logger.error(s"Plan.payPalIpn invalid data ${err.toString}")
            BadRequest
        ,
        ipn =>
          env.plan.api.payPal.onLegacyCharge(
            ipn,
            ip = req.ipAddress,
            key = get("key") | "N/A"
          ) inject Ok
      )
