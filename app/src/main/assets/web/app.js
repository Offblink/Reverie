/* Reverie 应用层：四视图接线 + 选择态。引擎事件（M2 起）在 Host 钩子里接。 */
(function () {
  "use strict";

  Zoom.init('#screen');
  var $ = function (id) { return document.getElementById(id); };

  /* ---------- 主题（SPEC §5.4 十二主题） ---------- */
  var THEMES = [
    { id: 'ghost',    name: '鬼故事',   desc: '怪谈 · 中式恐怖',   g1: '#3B3D5C', g2: '#12131F' },
    { id: 'mystery',  name: '悬疑',     desc: '线索 · 反转',       g1: '#4A5568', g2: '#1A202C' },
    { id: 'love',     name: '恋爱',     desc: '暧昧 · 心动',       g1: '#B4577A', g2: '#5C2A42' },
    { id: 'timetravel', name: '穿越',   desc: '时代错位',          g1: '#8A6F3E', g2: '#3E2F1A' },
    { id: 'fantasy',  name: '奇幻',     desc: '魔法 · 异世界',     g1: '#5B4B8A', g2: '#241C3E' },
    { id: 'scifi',    name: '科幻',     desc: '深空 · 设定',       g1: '#2F5D62', g2: '#12262B' },
    { id: 'wuxia',    name: '武侠',     desc: '江湖 · 恩仇',       g1: '#6B4A2F', g2: '#2B1B10' },
    { id: 'palace',   name: '宫斗',     desc: '权谋 · 步步惊心',   g1: '#7A3B4E', g2: '#33121C' },
    { id: 'campus',   name: '校园',     desc: '青春 · 成长',       g1: '#3E6B4F', g2: '#16301F' },
    { id: 'healing',  name: '治愈',     desc: '慢节奏 · 助眠',     g1: '#4E6B7A', g2: '#1B2B33' },
    { id: 'fairy',    name: '童话',     desc: '适龄 · 奇幻',       g1: '#7A6B9E', g2: '#2E2645' },
    { id: 'doomsday', name: '末日',     desc: '生存 · 人性',       g1: '#6B5B3E', g2: '#2B2416' }
  ];
  var sel = { theme: null, tier: null };

  var grid = $('theme-grid');
  THEMES.forEach(function (t) {
    var b = document.createElement('button');
    b.className = 'theme-card';
    b.dataset.theme = t.id;
    b.innerHTML = '<div class="mark" style="--g1:' + t.g1 + ';--g2:' + t.g2 + '"></div>' +
      '<div class="name">' + esc(t.name) + '</div><div class="desc">' + esc(t.desc) + '</div>';
    b.addEventListener('click', function () {
      sel.theme = t.id;
      [].forEach.call(grid.children, function (c) { c.classList.toggle('sel', c === b); });
      syncStart();
    });
    grid.appendChild(b);
  });

  $('tier-row').addEventListener('click', function (e) {
    var p = e.target.closest('.tier-pill');
    if (!p) return;
    sel.tier = p.dataset.tier;
    [].forEach.call(this.children, function (c) { c.classList.toggle('sel', c === p); });
    syncStart();
  });

  function syncStart() {
    $('start-btn').classList.toggle('off', !(sel.theme && sel.tier));
  }

  $('start-btn').addEventListener('click', function () {
    var r = bridge('newStory', { theme: sel.theme, tier: sel.tier });
    if (!r || !r.id) { toast('还没有配置模型 —— 先到设置里填好 endpoint 与 key'); return; }
    zoomToStory();
  });

  /* ---------- 视图导航 ---------- */
  var V = { home: $('v-home'), shelf: $('v-shelf'), story: $('v-story'), settings: $('v-settings') };

  // 主页入 zoom 栈根：否则 depth 从 0 起算，故事页 depth=1，
  // Host.back() 的 depth>1 判不中，系统返回键会直接退出 app（实测踩过）。
  Zoom.root(V.home);

  function zoomToStory() { Zoom.to($('start-btn'), V.story); }

  $('go-shelf').addEventListener('click', function () { renderShelf(); V.shelf.classList.add('on'); });
  $('go-settings').addEventListener('click', function () { loadSettings(); V.settings.classList.add('on'); });
  [].forEach.call(document.querySelectorAll('[data-back]'), function (b) {
    b.addEventListener('click', function () {
      var page = b.closest('.vpage');
      if (page === V.story) { micAbort(); if (Zoom.depth() > 1) Zoom.back(); }
      else page.classList.remove('on');
    });
  });

  /* Android 返回键：true = 页面消费 */
  Host.back = function () {
    if (V.settings.classList.contains('on')) { V.settings.classList.remove('on'); return true; }
    if (V.shelf.classList.contains('on')) { V.shelf.classList.remove('on'); return true; }
    if (V.story.classList.contains('on')) {
      var ok = Zoom.depth() > 1;
      console.log('reverie:back story-on depth=' + Zoom.depth() + ' consume=' + ok);
      if (ok) { micAbort(); Zoom.back(); }
      return ok;
    }
    console.log('reverie:back no-overlay-page');
    return false;
  };

  /* ---------- 书架 ---------- */
  function renderShelf() {
    var list = bridge('listStories') || [];
    var box = $('shelf-list');
    box.innerHTML = '';
    $('shelf-empty').hidden = list.length > 0;
    $('shelf-count').textContent = list.length ? list.length + ' 个' : '';
    list.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'shelf-item';
      b.innerHTML = '<div class="row1"><span class="title">' + esc(s.title || '未命名') + '</span>' +
        '<span class="badge">' + (s.status === 'done' ? '已完结' : s.progress || '进行中') + '</span></div>' +
        '<div class="meta">' + esc(s.themeName || s.theme) + ' · ' + esc(s.tierName || s.tier) + '</div>';
      b.addEventListener('click', function () {
        var r = bridge('openStory', { id: s.id });
        if (!r) { toast('打开失败'); return; }
        V.shelf.classList.remove('on');
        Zoom.to($('go-shelf'), V.story);
      });
      box.appendChild(b);
    });
  }

  /* ---------- 设置 ---------- */
  function loadSettings() {
    var st = bridge('getSettings') || {};
    $('in-endpoint').value = st.endpoint || '';
    $('in-key').value = '';                 // key 永不回显
    $('in-chat').value = st.chatModel || '';
    $('in-asr').value = st.asrModel || '';
    $('in-rate').value = st.ttsRate != null ? Number(st.ttsRate).toFixed(1) : '0.9';
    $('in-mic').value = st.micWindowSec != null ? st.micWindowSec : '10';
    $('set-hint').textContent = st.hasKey ? '已配置 key' : '尚未配置 key';
  }

  $('save-btn').addEventListener('click', function () {
    var patch = {
      endpoint: $('in-endpoint').value.trim(),
      chatModel: $('in-chat').value.trim(),
      asrModel: $('in-asr').value.trim(),
      ttsRate: parseFloat($('in-rate').value) || 0.9,
      micWindowSec: parseInt($('in-mic').value, 10) || 10
    };
    var k = $('in-key').value.trim();
    if (k) patch.apiKey = k;               // 空 = 不改
    var r = bridge('setSettings', patch);
    toast(r && r.ok ? '已保存' : '保存失败');
    loadSettings();
  });

  $('test-btn').addEventListener('click', function () {
    var r = bridge('testEndpoint');
    if (!r) { toast('引擎未就绪（M2 接入）'); return; }
    toast(r.ok ? '连通 ' + r.latencyMs + ' ms' : (r.msg || '不通'));
  });

  /* ---------- 故事页渲染 ---------- */
  var storyFlow = $('story-flow'), storyDock = $('story-dock');
  var curIdx = -1;              // 正在流式渲染的段
  var pendingDock = null;       // segment-done 已到、等 tts idle 才弹 {index, options, isFinal}
  var followMode = true;        // 跟读滚动：用户上滑离开底部即关，回底部再开
  var degradedToastShown = false;

  function tierName(t) { return t === 'medium' ? '中' : t === 'long' ? '长' : '短'; }

  /** 与 Java splitSentences 同规则切句（高亮按句文本匹配，两边必须一致） */
  function splitSents(t) {
    var out = [], cur = '';
    if (!t) return out;
    for (var i = 0; i < t.length; i++) {
      cur += t[i];
      if ('。！？!?…'.indexOf(t[i]) >= 0) { var s = cur.trim(); if (s) out.push(s); cur = ''; }
    }
    var tail = cur.trim();
    if (tail) out.push(tail);
    return out;
  }

  function fillSpans(p, text) {
    p.innerHTML = '';
    splitSents(text).forEach(function (s) {
      var sp = document.createElement('span');
      sp.className = 'sent';
      sp.textContent = s;
      p.appendChild(sp);
    });
  }

  /** 用户上滑离开底部 → 停止跟读；回底部恢复 */
  storyFlow.addEventListener('scroll', function () {
    var gap = storyFlow.scrollHeight - storyFlow.scrollTop - storyFlow.clientHeight;
    followMode = gap < 60;
  }, { passive: true });

  /** 全量快照重建（打开/续听/结构变化）。
      busy 必须从快照顶层传入——story 对象里没有 busy 字段，
      在 story 上取 st.busy 恒为 undefined，空段快照会误弹重试坞（实测踩过）。 */
  function renderStory(st, busy) {
    if (!st) return;
    $('story-p1').textContent = st.title || '未命名';
    $('story-p2').textContent = st.status === 'done'
      ? st.themeName + ' · ' + tierName(st.tier) + ' · 已完结'
      : st.themeName + ' · ' + tierName(st.tier) + ' · ' + st.segments.length + '/' + st.choicesTarget + ' 段';
    storyFlow.innerHTML = '';
    st.segments.forEach(function (seg) {
      var p = document.createElement('p');
      p.className = 'seg';
      p.dataset.idx = seg.index;
      fillSpans(p, seg.text);
      storyFlow.appendChild(p);
    });
    storyFlow.scrollTop = storyFlow.scrollHeight;
    // 空故事（首段生成失败留下的档）→ 给重试入口，别开成死页
    if (!st.segments.length) {
      if (!busy) showRetry('还没有生成内容');
      else storyDock.classList.remove('on');   // 正在生成首段：不弹任何坞
      return;
    }
    // 未回答的选择点 → 弹选项坞（续听落点）。
    // 例外：pendingDock 命中最后一段 = 正在朗读中 → 显式清空 dock，读完才弹（SPEC §6）。
    var last = st.segments[st.segments.length - 1];
    var waitingRead = pendingDock && last && pendingDock.index === last.index;
    if (waitingRead) {
      storyDock.classList.remove('on');
    } else if (last && !busy && !last.isFinal && last.choice === -1 && last.options.length) {
      showDock(last.options);
    } else {
      storyDock.classList.remove('on');
    }
  }

  function showDock(options) {
    storyDock.innerHTML = '';
    options.forEach(function (opt, i) {
      var b = document.createElement('button');
      b.className = 'opt-btn glass';
      b.innerHTML = '<span class="no">' + '一二三四五'[i] + '</span>' + esc(opt);
      b.addEventListener('click', function () {
        var r = bridge('choose', { index: i });
        if (!r || !r.ok) { toast('暂不可选'); return; }
        storyDock.classList.remove('on');
      });
      storyDock.appendChild(b);
    });
    storyDock.classList.add('on');
    storyFlow.scrollTop = storyFlow.scrollHeight;
    bridge('startAsr');   // 选项就位 → 自动开麦（SPEC §7；原生守卫前台/busy/已答）
  }

  function showRetry(msg) {
    storyDock.innerHTML = '';
    var hint = document.createElement('div');
    hint.className = 'tiny';
    hint.style.textAlign = 'center';
    hint.textContent = msg || '生成失败';
    var b = document.createElement('button');
    b.className = 'opt-btn glass';
    b.style.textAlign = 'center';
    b.textContent = '重试';
    b.addEventListener('click', function () {
      bridge('retry');
      storyDock.classList.remove('on');
    });
    storyDock.appendChild(hint);
    storyDock.appendChild(b);
    storyDock.classList.add('on');
  }

  /* ---------- 引擎事件 ---------- */
  Host.onState = function (st) {
    if (st && st.story) renderStory(st.story, st.busy);
    if (st && st.ttsDegraded && !degradedToastShown) {
      degradedToastShown = true;
      toast('这台设备没有中文语音引擎，已切换为纯文字模式');
    }
  };

  /** 高亮当前朗读句（按句文本定位——两边切句规则一致） */
  function highlightSentence(segIdx, text) {
    var p = storyFlow.querySelector('.seg[data-idx="' + segIdx + '"]');
    if (!p) return;
    var prev = p.querySelector('.sent.cur');
    if (prev) prev.classList.remove('cur');
    var spans = p.querySelectorAll('.sent');
    for (var i = 0; i < spans.length; i++) {
      if (spans[i].textContent === String(text).trim()) {
        spans[i].classList.add('cur');
        if (followMode) spans[i].scrollIntoView({ block: 'center', behavior: 'smooth' });
        return;
      }
    }
  }

  function clearHighlight() {
    var prev = storyFlow.querySelector('.sent.cur');
    if (prev) prev.classList.remove('cur');
  }

  Host.onEvent = function (ev) {
    if (!ev) return;
    switch (ev.type) {
      case 'segment-delta': {
        if (ev.index !== curIdx) {          // 新段第一句 → 开块
          curIdx = ev.index;
          var p = document.createElement('p');
          p.className = 'seg';
          p.dataset.idx = ev.index;
          storyFlow.appendChild(p);
        }
        var el = storyFlow.querySelector('.seg[data-idx="' + ev.index + '"]');
        if (el) {
          var sp = document.createElement('span');
          sp.className = 'sent';
          sp.textContent = ev.text;
          el.appendChild(sp);
          if (followMode) storyFlow.scrollTop = storyFlow.scrollHeight;
        }
        $('story-p2').textContent = '正在编织第 ' + (ev.index + 1) + ' 段…';
        break;
      }
      case 'segment-done': {
        curIdx = -1;
        var el2 = storyFlow.querySelector('.seg[data-idx="' + ev.index + '"]');
        if (el2) fillSpans(el2, ev.text);   // 全文重建（切句与 TTS 对齐）
        // 文本到齐≠可以选：等 TTS 念完该段（tts idle）才弹（SPEC §6）
        pendingDock = { index: ev.index, options: ev.options, isFinal: ev.isFinal };
        break;
      }
      case 'tts-state': {
        if (ev.state === 'speaking') {
          highlightSentence(ev.index, ev.text);
        } else if (ev.state === 'idle') {
          clearHighlight();
          if (pendingDock && pendingDock.index === ev.index) {
            var pd = pendingDock;
            pendingDock = null;
            if (pd.isFinal) {
              storyDock.classList.remove('on');
              $('story-p2').textContent = '梦已完结';
              toast('故事讲完了，晚安');
            } else {
              showDock(pd.options);
            }
          }
        } else if (ev.state === 'replay-done') {
          clearHighlight();
          replaying = false;
          toast('重听结束');
        } else if (ev.state === 'degraded') {
          if (!degradedToastShown) {
            degradedToastShown = true;
            toast('这台设备没有中文语音引擎，已切换为纯文字模式');
          }
          // 降级后段读完事件仍会来（引擎按读完结算），pendingDock 照常消费
        }
        break;
      }
      case 'choice-made': {
        pendingDock = null;
        asrRingStop();
        $('story-p2').textContent = '继续…';
        break;
      }
      case 'asr-state': {
        if (ev.state === 'listening') {
          asrRingStart(ev.windowSec || 10);
        } else {
          asrRingStop();
          if (ev.state === 'timeout') {
            toast(ev.heard ? '听到「' + ev.heard + '」但没对上选项 —— 点一下吧'
                           : '没听到选择，选项还在等着');
          } else if (ev.state === 'error') {
            toast(ev.msg || '语音识别失败，可点击选择');
          }
          // matched：choice-made 紧随其后，UI 交给它
        }
        break;
      }
      case 'story-done': break;
      case 'error': {
        if (ev.stage === 'llm') { pendingDock = null; showRetry(ev.msg); }
        else toast(ev.msg);
        break;
      }
    }
  };

  /* ---------- 重听（M4，SPEC §3.2） ---------- */
  var replaying = false;
  $('replay-btn').addEventListener('click', function () {
    if (replaying) {
      replaying = false;
      bridge('stopReplay');
      clearHighlight();
      toast('停止重听');
      return;
    }
    var st = bridge('getState');
    var story = st && st.story;
    if (!story || !story.segments || !story.segments.length) { toast('还没有内容可重听'); return; }
    // 活跃故事 → 重播当前段；已完结 → 从头重听全文
    var r = story.status === 'done'
      ? bridge('replay', {})
      : bridge('replaySegment', { index: story.segments.length - 1 });
    if (!r || !r.ok) { toast(st.busy ? '正在生成，稍后再试' : '暂不可重听'); return; }
    replaying = true;
  });

  /* ---------- 麦克风（M5，SPEC §7） ---------- */
  var micBtn = $('mic-btn');
  var asr = { live: false, timer: 0, endsAt: 0, dur: 0 };

  function ringSet(pct) { micBtn.style.setProperty('--p', Math.max(0, Math.min(100, pct))); }

  function asrRingStart(sec) {
    asr.live = true;
    asr.dur = sec;
    asr.endsAt = Date.now() + sec * 1000;
    micBtn.classList.add('live');
    clearInterval(asr.timer);
    asr.timer = setInterval(function () {
      ringSet((asr.endsAt - Date.now()) / (asr.dur * 1000) * 100);
      if (Date.now() >= asr.endsAt) asrRingStop();   // 原生 VAD 是真值，这里只是视觉对齐
    }, 200);
  }

  function asrRingStop() {
    asr.live = false;
    clearInterval(asr.timer);
    micBtn.classList.remove('live');
    ringSet(0);
  }

  /** 离开故事页：关麦（原生也会在退后台/换故事时兜底关） */
  function micAbort() {
    if (asr.live) { bridge('stopAsr'); asrRingStop(); }
  }

  micBtn.addEventListener('click', function () {
    if (asr.live) { micAbort(); return; }
    var r = bridge('startAsr');
    if (!r) { toast('语音选择暂不可用'); return; }
    if (r.needPermission) return;   // 系统授权弹窗中；授权后 listening 事件亮环
    if (!r.ok) { toast('现在还不能录音'); return; }
    // ok=true 但 listening 事件稍后到，环由事件点亮
  });

  /* ---------- 桥自检 ---------- */
  syncStart();
  var st = bridge('getState');
  console.log('reverie:app state=' + JSON.stringify(st));
})();
