/* Reverie 应用层：四视图接线 + 选择态。引擎事件（M2 起）在 Host 钩子里接。
   M12 起为纯文字：麦克风、重听、音色抽屉、诊断面板随语音链路一并删除。 */
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
  var sel = { theme: null, tier: null, pov: 'third' };   // 人称默认第三人称（预选）

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

  $('pov-row').addEventListener('click', function (e) {
    var p = e.target.closest('.tier-pill');
    if (!p) return;
    sel.pov = p.dataset.pov;
    [].forEach.call(this.children, function (c) { c.classList.toggle('sel', c === p); });
  });

  function syncStart() {
    $('start-btn').classList.toggle('off', !(sel.theme && sel.tier));
  }

  $('start-btn').addEventListener('click', function () {
    var r = bridge('newStory', { theme: sel.theme, tier: sel.tier, pov: sel.pov });
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
  $('go-settings').addEventListener('click', function () {
    loadSettings();
    V.settings.classList.add('on');
  });
  [].forEach.call(document.querySelectorAll('[data-back]'), function (b) {
    b.addEventListener('click', function () {
      var page = b.closest('.vpage');
      if (page === V.story) { if (Zoom.depth() > 1) Zoom.back(); }
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
      if (ok) Zoom.back();
      return ok;
    }
    console.log('reverie:back no-overlay-page');
    return false;
  };

  /* ---------- 书架 ---------- */
  var confirmEl = null;

  /** 删除确认浮层（M6）：玻璃底抽屉式，after = 删除成功后的收尾（刷新列表） */
  function askDelete(s, after) {
    if (!confirmEl) {
      confirmEl = document.createElement('div');
      confirmEl.className = 'confirm-mask';
      confirmEl.innerHTML = '<div class="confirm-panel glass"><div class="ct"></div>' +
        '<div class="crow"><button class="cno">取消</button><button class="cdel">删除</button></div></div>';
      document.body.appendChild(confirmEl);
      confirmEl.querySelector('.cno').addEventListener('click', function () {
        confirmEl.classList.remove('on');
      });
      confirmEl.addEventListener('click', function (e) {
        if (e.target === confirmEl) confirmEl.classList.remove('on');   // 点遮罩取消
      });
    }
    confirmEl.querySelector('.ct').textContent =
      '删除《' + (s.title || '未命名') + '》？已看的和没写完的都会没掉。';
    confirmEl.querySelector('.cdel').onclick = function () {
      bridge('deleteStory', { id: s.id });
      confirmEl.classList.remove('on');
      toast('已删除');
      after();
    };
    confirmEl.classList.add('on');
  }

  function renderShelf() {
    var list = bridge('listStories') || [];
    var box = $('shelf-list');
    box.innerHTML = '';
    $('shelf-empty').hidden = list.length > 0;
    $('shelf-hint').hidden = list.length === 0;
    $('shelf-count').textContent = list.length ? list.length + ' 个' : '';
    list.forEach(function (s) {
      var b = document.createElement('button');
      b.className = 'shelf-item';
      b.innerHTML = '<div class="row1"><span class="title">' + esc(s.title || '未命名') + '</span>' +
        '<span class="badge">' + (s.status === 'done' ? '已完结' : s.progress || '进行中') + '</span></div>' +
        '<div class="meta">' + esc(s.themeName || s.theme) + ' · ' + esc(s.tierName || s.tier) + '</div>';
      // 长按 600ms → 删除确认；位移 >12px 视为滚动取消
      var lpTimer = 0, lpFired = false, lpX = 0, lpY = 0;
      b.addEventListener('pointerdown', function (e) {
        lpFired = false;
        lpX = e.clientX; lpY = e.clientY;
        lpTimer = setTimeout(function () {
          lpTimer = 0;
          lpFired = true;
          if (navigator.vibrate) navigator.vibrate(20);
          askDelete(s, renderShelf);
        }, 600);
      });
      b.addEventListener('pointermove', function (e) {
        if (lpTimer && (Math.abs(e.clientX - lpX) > 12 || Math.abs(e.clientY - lpY) > 12)) {
          clearTimeout(lpTimer);
          lpTimer = 0;
        }
      });
      b.addEventListener('pointerup', function () { clearTimeout(lpTimer); lpTimer = 0; });
      b.addEventListener('pointercancel', function () { clearTimeout(lpTimer); lpTimer = 0; });
      b.addEventListener('click', function () {
        if (lpFired) { lpFired = false; return; }   // 长按触发过删除 → 不再进打开
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
    $('set-hint').textContent = st.hasKey ? '已配置 key' : '尚未配置 key';
  }

  $('save-btn').addEventListener('click', function () {
    var patch = {
      endpoint: $('in-endpoint').value.trim(),
      chatModel: $('in-chat').value.trim()
    };
    var k = $('in-key').value.trim();
    if (k) patch.apiKey = k;               // 空 = 不改
    var r = bridge('setSettings', patch);
    toast(r && r.ok ? '已保存' : '保存失败');
    loadSettings();
  });

  $('test-btn').addEventListener('click', function () {
    var r = bridge('testEndpoint');
    if (!r) { toast('引擎未就绪'); return; }
    toast(r.ok ? '连通 ' + r.latencyMs + ' ms' : (r.msg || '不通'));
  });

  /* ---------- 故事页渲染 ---------- */
  var storyFlow = $('story-flow'), storyDock = $('story-dock');
  var curIdx = -1;              // 正在流式渲染的段
  var followMode = true;        // 跟读滚动：用户上滑离开底部即关，回底部再开
  var curStoryId = null;

  function tierName(t) { return t === 'medium' ? '中' : t === 'long' ? '长' : '短'; }

  /* ---------- 生成中的等待反馈（M11） ----------
     LLM 到首句前有一段纯等待，页面不再静默：正文流末尾浮一块呼吸骨架，
     首个 segment-delta 即撤；busy 快照兜底（换故事/页面重载也能亮起）。
     骨架贴正文末尾，不遮正文；关动效由 glass.css 全局降级 + 静态形态兜底。 */
  var LOADING_ID = 'story-loading';
  var loading = false;

  function renderLoading() {
    if (!loading) return;
    var el = document.createElement('p');
    el.className = 'seg loading-seg';
    el.id = LOADING_ID;
    el.innerHTML = '<div class="bar"></div><div class="bar"></div><div class="bar"></div>' +
      '<div class="whisper"><span class="dot"></span>正在编织…</div>';
    storyFlow.appendChild(el);
  }

  function showLoading() {
    loading = true;
    var old = $(LOADING_ID);
    if (old) old.remove();
    renderLoading();
    if (followMode) storyFlow.scrollTop = storyFlow.scrollHeight;
  }

  function hideLoading() {
    loading = false;
    var old = $(LOADING_ID);
    if (old) old.remove();
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
      p.textContent = seg.text;
      storyFlow.appendChild(p);
    });
    renderLoading();          // 全量重建会清掉骨架 → 按相位补回
    storyFlow.scrollTop = storyFlow.scrollHeight;
    // 空故事（首段生成失败留下的档）→ 给重试入口，别开成死页
    if (!st.segments.length) {
      if (!busy) { hideLoading(); showRetry('还没有生成内容'); }
      else storyDock.classList.remove('on');   // 正在生成首段：不弹任何坞
      return;
    }
    // 未回答的选择点 → 弹选项坞（续听落点）
    var last = st.segments[st.segments.length - 1];
    if (last && !busy && !last.isFinal && last.choice === -1 && last.options.length) {
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
    if (st && st.story) {
      if (st.story.id !== curStoryId) {   // 换故事/开新档：骨架与流式段号作废
        curStoryId = st.story.id;
        curIdx = -1;
        hideLoading();
      }
      renderStory(st.story, st.busy);
      // busy 快照 = 等首句的权威信号（请求已发、首句未到）；正在流式（curIdx 有效）时不重复点亮
      if (st.busy && !loading && curIdx < 0) showLoading();
    }
  };

  Host.onEvent = function (ev) {
    if (!ev) return;
    switch (ev.type) {
      case 'segment-delta': {
        hideLoading();            // 首句已到
        if (ev.index !== curIdx) {          // 新段第一句 → 开块
          curIdx = ev.index;
          var p = document.createElement('p');
          p.className = 'seg';
          p.dataset.idx = ev.index;
          storyFlow.appendChild(p);
        }
        var el = storyFlow.querySelector('.seg[data-idx="' + ev.index + '"]');
        if (el) {
          el.appendChild(document.createTextNode(ev.text));
          if (followMode) storyFlow.scrollTop = storyFlow.scrollHeight;
        }
        $('story-p2').textContent = '正在编织第 ' + (ev.index + 1) + ' 段…';
        break;
      }
      case 'segment-done': {
        curIdx = -1;
        var el2 = storyFlow.querySelector('.seg[data-idx="' + ev.index + '"]');
        if (el2) el2.textContent = ev.text;   // 全文重建，与引擎落盘的正文一致
        $('story-p2').textContent = ev.isFinal
          ? '梦已完结'
          : '第 ' + (ev.index + 1) + '/' + ev.total + ' 段 · 等你选';
        if (ev.isFinal) {
          storyDock.classList.remove('on');
          toast('故事讲完了，晚安');
        } else {
          showDock(ev.options);   // 纯文字：文本到齐即可选
        }
        break;
      }
      case 'choice-made': {
        $('story-p2').textContent = '继续…';
        break;
      }
      case 'story-done': break;
      case 'error': {
        hideLoading();            // 失败/重试坞接管，避免骨架与重试按钮同屏
        if (ev.stage === 'llm') showRetry(ev.msg);
        else toast(ev.msg);
        break;
      }
    }
  };

  /* ---------- 桥自检 ---------- */
  syncStart();
  var bootSt = bridge('getState');
  console.log('reverie:app state=' + JSON.stringify(bootSt));
  if (bootSt) Host.onState(bootSt);   // 页面重载时补渲染（含生成中 → 骨架）
})();
