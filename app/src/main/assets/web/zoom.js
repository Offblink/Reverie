/* ============================================================
   Reverie · 整页缩放转场引擎
   移植自 Mobile-Design blinvo-design/zoom-transition（源头 Offblink/Prism
   Ui.rectOnScreen / Ui.zoomIn / shrinkFinish），改为通用注册式 API。
   契约：进场 280ms / 退场 240ms，曲线 fast_out_slow_in（页面级转场唯一
   不回弹的例外——整页越过 100% 再回落读起来像故障）。非等比 scale，
   起始态精确填满源元素矩形、不裁不补。
   ============================================================ */
var Zoom = (function () {
  var IDENTITY = 'translate(0px,0px) scale(1,1)'; // 不能写 none：none 不参与函数式补间
  var EASE = 'cubic-bezier(.4,0,.2,1)';           // fast_out_slow_in
  var IN_MS = 280, OUT_MS = 240;
  var screenEl = null;
  var busy = false;
  var stack = [];   // [{page, from}] —— from 是放大进来的源元素，退场要缩回它

  function init(screenSelector) {
    screenEl = document.querySelector(screenSelector);
  }

  /** 源元素相对屏幕的矩形 */
  function rectIn(el) {
    var s = screenEl.getBoundingClientRect();
    var r = el.getBoundingClientRect();
    return { x: r.left - s.left, y: r.top - s.top, w: r.width, h: r.height };
  }

  /** 把整页映射进源矩形：origin 左上角 + 非等比 scale */
  function fit(r) {
    return 'translate(' + r.x + 'px,' + r.y + 'px) scale(' +
      (r.w / screenEl.clientWidth) + ',' + (r.h / screenEl.clientHeight) + ')';
  }

  /** transform 补间结束：transitionend 为主，超时兜底防事件被吞卡死 */
  function whenDone(el, ms, fn) {
    var settled = false;
    function settle() {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      el.removeEventListener('transitionend', onEnd);
      fn();
    }
    function onEnd(e) {
      if (e.target !== el || e.propertyName !== 'transform') return;
      settle();
    }
    var timer = setTimeout(settle, ms + 400);
    el.addEventListener('transitionend', onEnd);
  }

  /** 从 fromEl 放大进入 page，并压栈 */
  function to(fromEl, page) {
    if (busy || stack[stack.length - 1] && stack[stack.length - 1].page === page) return;
    busy = true;
    var r = rectIn(fromEl);
    page.style.transition = 'none';
    page.style.transform = fit(r);
    page.classList.add('on');
    void page.offsetWidth; // 同帧改动会被合并，强制重排让起点生效
    page.style.transition = 'transform ' + IN_MS + 'ms ' + EASE;
    page.style.transform = IDENTITY;
    whenDone(page, IN_MS, function () {
      page.style.transition = '';
      page.style.transform = ''; // 收干净：长期挂着的合成层让文字发虚
      busy = false;
    });
    stack.push({ page: page, from: fromEl });
  }

  /** 顶层页缩回源元素后收场，弹栈 */
  function back() {
    if (busy || stack.length < 2) return false;
    var top = stack.pop();
    busy = true;
    top.page.style.transition = 'none';
    top.page.style.transform = IDENTITY;
    void top.page.offsetWidth;
    top.page.style.transition = 'transform ' + OUT_MS + 'ms ' + EASE;
    top.page.style.transform = fit(rectIn(top.from));
    whenDone(top.page, OUT_MS, function () {
      top.page.classList.remove('on');
      top.page.style.transition = '';
      top.page.style.transform = '';
      busy = false;
    });
    return true;
  }

  /** 根页直切（不经缩放）：启动/重置用 */
  function root(page) {
    while (stack.length) stack.pop().page.classList.remove('on');
    page.classList.add('on');
  }

  function depth() { return stack.length; }

  return { init: init, to: to, back: back, root: root, depth: depth };
})();
