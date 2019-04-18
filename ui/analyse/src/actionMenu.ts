import { empty } from 'common';
import { h } from 'snabbdom'
import { VNode } from 'snabbdom/vnode'
import { Hooks } from 'snabbdom/hooks'
import { MaybeVNodes } from './interfaces';
import { AutoplayDelay } from './autoplay';
import { boolSetting, BoolSetting } from './boolSetting';
import AnalyseCtrl from './ctrl';
import { cont as contRoute } from 'game/router';
import { synthetic, bind, dataIcon } from './util';
import * as pgnExport from './pgnExport';

interface AutoplaySpeed {
  name: string;
  delay: AutoplayDelay;
}

const baseSpeeds: AutoplaySpeed[] = [{
  name: 'fast',
  delay: 1000
}, {
  name: 'slow',
  delay: 5000
}];

const realtimeSpeed: AutoplaySpeed = {
  name: 'realtimeReplay',
  delay: 'realtime'
};

const cplSpeed: AutoplaySpeed = {
  name: 'byCPL',
  delay: 'cpl'
};

function deleteButton(ctrl: AnalyseCtrl, userId: string | null): VNode | undefined {
  const g = ctrl.data.game;
  if (g.source === 'import' &&
    g.importedBy && g.importedBy === userId)
  return h('form.delete', {
    attrs: {
      method: 'post',
      action: '/' + g.id + '/delete'
    },
    hook: bind('submit', _ => confirm(ctrl.trans.noarg('deleteThisImportedGame')))
  }, [
    h('button.button.text.thin', {
      attrs: {
        type: 'submit',
        'data-icon': 'q'
      }
    }, ctrl.trans.noarg('delete'))
  ]);
  return;
}

function autoplayButtons(ctrl: AnalyseCtrl): VNode {
  const d = ctrl.data;
  const speeds = [
    ...baseSpeeds,
    ...(empty(d.game.moveCentis) ? [] : [realtimeSpeed]),
    ...(d.analysis ? [cplSpeed] : [])
  ];
  return h('div.autoplay', speeds.map(speed => {
    return h('a.fbt', {
      class: { active: ctrl.autoplay.active(speed.delay) },
      hook: bind('click', () => ctrl.togglePlay(speed.delay), ctrl.redraw)
    }, ctrl.trans.noarg(speed.name));
  }));
}

function rangeConfig(read: () => number, write: (value: number) => void): Hooks {
  return {
    insert: vnode => {
      const el = vnode.elm as HTMLInputElement;
      el.value = '' + read();
      el.addEventListener('input', _ => write(parseInt(el.value)));
      el.addEventListener('mouseout', _ => el.blur());
    }
  };
}

function formatHashSize(v: number): string {
  if (v < 1000) return v + 'MB';
  else return Math.round(v / 1024) + 'GB';
}

function hiddenInput(name: string, value: string) {
  return h('input', {
    attrs: { 'type': 'hidden', name, value }
  });
}

function studyButton(ctrl: AnalyseCtrl) {
  if (ctrl.study && ctrl.embed && !ctrl.ongoing) return h('a.fbt', {
    attrs: {
      href: '/study/' + ctrl.study.data.id + '#' + ctrl.study.currentChapter().id,
      target: '_blank'
    }
  }, [
    h('i.icon', {
      attrs: dataIcon('4')
    }),
    ctrl.trans.noarg('openStudy')
  ]);
  if (ctrl.study || ctrl.ongoing) return;
  const realGame = !synthetic(ctrl.data);
  return h('form', {
    attrs: {
      method: 'post',
      action: '/study/as'
    },
    hook: bind('submit', e => {
      const pgnInput = (e.target as HTMLElement).querySelector('input[name=pgn]') as HTMLInputElement;
      if (pgnInput) pgnInput.value = pgnExport.renderFullTxt(ctrl);
    })
  }, [
    realGame ? hiddenInput('gameId', ctrl.data.game.id) : hiddenInput('pgn', ''),
    hiddenInput('orientation', ctrl.chessground.state.orientation),
    hiddenInput('variant', ctrl.data.game.variant.key),
    hiddenInput('fen', ctrl.tree.root.fen),
    h('button.fbt', { attrs: { type: 'submit' } }, [
      h('i.icon', { attrs: dataIcon('4') }),
      'Study'
    ])
  ]);
}

export class Ctrl {
  open: boolean = false;
  toggle = () => this.open = !this.open;
}

export function view(ctrl: AnalyseCtrl): VNode {
  const d = ctrl.data,
  noarg = ctrl.trans.noarg,
  canContinue = !ctrl.ongoing && !ctrl.embed && d.game.variant.key === 'standard',
  ceval = ctrl.getCeval(),
  mandatoryCeval = ctrl.mandatoryCeval();

  const tools: MaybeVNodes = [
    h('div.tools', [
      h('a.fbt', {
    hook: bind('click', ctrl.flip)
  }, [
        h('i.icon', { attrs: dataIcon('B') }),
        noarg('flipBoard')
      ]),
      ctrl.ongoing ? null : h('a.fbt', {
        attrs: {
          href: d.userAnalysis ? '/editor?fen=' + ctrl.node.fen : '/' + d.game.id + '/edit?fen=' + ctrl.node.fen,
          rel: 'nofollow',
          target: ctrl.embed ? '_blank' : ''
        }
      }, [
        h('i.icon', { attrs: dataIcon('m') }),
        noarg('boardEditor')
      ]),
      canContinue ? h('a.fbt', {
        hook: bind('click', _ => $.modal($('.continue_with.g_' + d.game.id)))
      }, [
        h('i.icon', {
          attrs: dataIcon('U')
        }),
        noarg('continueFromHere')
      ]) : null,
      studyButton(ctrl)
    ])
  ];

  const cevalConfig: MaybeVNodes = (ceval && ceval.possible && ceval.allowed()) ? ([
    h('h2', noarg('computerAnalysis'))
  ] as MaybeVNodes).concat([
    ctrlBoolSetting({
      name: 'enable',
      title: mandatoryCeval ? "Required by practice mode" : window.lichess.engineName,
      id: 'all',
      checked: ctrl.showComputer(),
      disabled: mandatoryCeval,
      change: ctrl.toggleComputer
    }, ctrl)
  ]).concat(
    ctrl.showComputer() ? [
      ctrlBoolSetting({
        name: 'bestMoveArrow',
        title: 'a',
        id: 'shapes',
        checked: ctrl.showAutoShapes(),
        change: ctrl.toggleAutoShapes
      }, ctrl),
      ctrlBoolSetting({
        name: 'evaluationGauge',
        id: 'gauge',
        checked: ctrl.showGauge(),
        change: ctrl.toggleGauge
      }, ctrl),
      ctrlBoolSetting({
        name: 'infiniteAnalysis',
        title: 'removesTheDepthLimit',
        id: 'infinite',
        checked: ceval.infinite(),
        change: ctrl.cevalSetInfinite
      }, ctrl),
      (id => {
        const max = 5;
        return h('div.setting', [
          h('label', { attrs: { 'for': id } }, noarg('multipleLines')),
          h('input#' + id, {
            attrs: {
              type: 'range',
              min: 1,
              max,
              step: 1
            },
            hook: rangeConfig(
              () => parseInt(ceval!.multiPv()),
              ctrl.cevalSetMultiPv)
          }),
          h('div.range_value', ceval.multiPv() + ' / ' + max)
        ]);
      })('analyse-multipv'),
      (ceval.pnaclSupported || ceval.wasmxSupported) ? (id => {
        let max = navigator.hardwareConcurrency;
        if (!max) return;
        if (max > 2) max--; // don't overload your computer, you dummy
        if (max > 8 && ceval.wasmxSupported) max = 8; // hard limit for now
        return h('div.setting', [
          h('label', { attrs: { 'for': id } }, noarg('cpus')),
          h('input#' + id, {
            attrs: {
              type: 'range',
              min: 1,
              max,
              step: 1
            },
            hook: rangeConfig(
              () => parseInt(ceval!.threads()),
              ctrl.cevalSetThreads)
          }),
          h('div.range_value', ceval.threads() + ' / ' + max)
        ]);
      })('analyse-threads') : null,
      (ceval.pnaclSupported && !ceval.wasmxSupported) ? (id => h('div.setting', [
        h('label', { attrs: { 'for': id } }, noarg('memory')),
        h('input#' + id, {
          attrs: {
            type: 'range',
            min: 4,
            max: 10,
            step: 1
          },
          hook: rangeConfig(
            () => Math.floor(Math.log2!(parseInt(ceval!.hashSize()))),
            v => ctrl.cevalSetHashSize(Math.pow(2, v)))
        }),
        h('div.range_value', formatHashSize(parseInt(ceval.hashSize())))
      ]))('analyse-memory') : null
    ] : []) : [];

    const notationConfig = [
      h('h2', noarg('preferences')),
      ctrlBoolSetting({
        name: noarg('inlineNotation'),
        title: 'Shift+I',
        id: 'inline',
        checked: ctrl.treeView.inline(),
        change(v) {
          ctrl.treeView.set(v);
          ctrl.actionMenu.toggle();
        }
      }, ctrl)
    ];

    return h('div.action_menu',
      tools
        .concat(notationConfig)
        .concat(cevalConfig)
        .concat(ctrl.mainline.length > 4 ? [h('h2', noarg('replayMode')), autoplayButtons(ctrl)] : [])
        .concat([
          deleteButton(ctrl, ctrl.opts.userId),
          canContinue ? h('div.continue_with.g_' + d.game.id, [
            h('a.button', {
              attrs: {
                href: d.userAnalysis ? '/?fen=' + ctrl.encodeNodeFen() + '#ai' : contRoute(d, 'ai') + '?fen=' + ctrl.node.fen,
                rel: 'nofollow'
              }
            }, noarg('playWithTheMachine')),
            h('br'),
            h('a.button', {
              attrs: {
                href: d.userAnalysis ? '/?fen=' + ctrl.encodeNodeFen() + '#friend' : contRoute(d, 'friend') + '?fen=' + ctrl.node.fen,
                rel: 'nofollow'
              }
            }, noarg('playWithAFriend'))
          ]) : null
        ])
    );
}

function ctrlBoolSetting(o: BoolSetting, ctrl: AnalyseCtrl) {
  return boolSetting(o, ctrl.trans, ctrl.redraw);
}
