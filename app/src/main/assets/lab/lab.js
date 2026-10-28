(function () {
  'use strict';
  const parameters = new URL(location.href).searchParams;
  document.documentElement.dataset.theme = parameters.get('theme') === 'dark' ? 'dark' : 'light';
  const sid = (parameters.get('sid') || 'preview').slice(0, 128);
  const tab = (parameters.get('tab') || 'single').slice(0, 128);
  const started = performance.now();
  const list = document.getElementById('events');
  let count = 0;
  const names = {
    BROWSER_READY:'探针已就绪', CANVAS_FINGERPRINT:'Canvas 渲染摘要', WEBGL_INFO:'WebGL 信息', WEBRTC_RESULT:'WebRTC 本地候选',
    VIEWPORT_CHANGED:'可视区变化', WEB_ZOOM:'缩放变化', BROWSER_SCROLL:'滚动', BROWSER_FOCUS:'焦点变化', BROWSER_VISIBILITY:'可见性变化',
    COPY:'复制', PASTE:'粘贴', SELECTION:'文本选择', LONG_PRESS:'长按菜单', DEBUG_SHORTCUT:'调试快捷键', DUPLICATE_PAGE:'发现同会话对照页',
    WATERMARK_CHECK:'水印核验', BROWSER_ANSWER:'选项变化', BROWSER_SUBMIT:'提交实测答案', FEATURE_UNAVAILABLE:'专项接口受限',
    BROWSER_DEVICE:'浏览器运行环境', BROWSER_ORIENTATION:'屏幕方向变化', BROWSER_FULL_SCREEN:'全屏状态变化'
  };
  function emit(kind, detail) {
    const record = { kind, detail: detail || {} };
    const item = document.createElement('li');
    const time = document.createElement('time');
    const text = document.createElement('span');
    time.textContent = ((performance.now() - started) / 1000).toFixed(1);
    text.textContent = (names[kind] || kind) + ' · ' + JSON.stringify(record.detail);
    item.append(time, text);
    const offset = list.scrollTop;
    list.prepend(item);
    if (offset > 4) list.scrollTop = offset + item.getBoundingClientRect().height;
    if (list.children.length > 60) list.lastChild.remove();
    document.getElementById('event-count').textContent = (++count) + ' 条';
    if (window.SentryBridge && typeof SentryBridge.postMessage === 'function') SentryBridge.postMessage(JSON.stringify(record));
  }
  function throttle(action, delay) {
    let pending = false;
    return function () { if (!pending) { pending = true; setTimeout(function () { pending = false; action(); }, delay); } };
  }
  function hash(value) {
    let result = 2166136261;
    for (let i = 0; i < value.length; i++) result = Math.imul(result ^ value.charCodeAt(i), 16777619) >>> 0;
    return result.toString(16).padStart(8, '0');
  }
  const device = { platform: navigator.platform || '', userAgent: navigator.userAgent || '', dpr: devicePixelRatio || 1,
    screenWidth: screen.width, screenHeight: screen.height, cores: navigator.hardwareConcurrency || 0 };
  document.getElementById('device-value').textContent = device.platform + ' / ' + device.screenWidth + ' × ' + device.screenHeight + ' / DPR ' + device.dpr;
  emit('BROWSER_DEVICE', device);
  const canvas = document.getElementById('fingerprint');
  try {
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#087f8c'; ctx.fillRect(0, 0, 260, 62);
    ctx.fillStyle = '#ffffff'; ctx.font = '600 18px system-ui'; ctx.fillText('考境哨兵 · Canvas', 12, 28);
    ctx.fillStyle = '#b3e4de'; ctx.font = '12px monospace'; ctx.fillText('LOCAL RENDER / v1', 12, 48);
    const fingerprint = hash(canvas.toDataURL());
    document.getElementById('canvas-value').textContent = fingerprint;
    emit('CANVAS_FINGERPRINT', { hash: fingerprint, supported: true });
  } catch (_) {
    document.getElementById('canvas-value').textContent = '接口不可用'; emit('FEATURE_UNAVAILABLE', { feature: 'canvas' });
  }
  try {
    const gl = document.createElement('canvas').getContext('webgl');
    if (!gl) throw new Error('unsupported');
    const debug = gl.getExtension('WEBGL_debug_renderer_info');
    const renderer = String(gl.getParameter(debug ? debug.UNMASKED_RENDERER_WEBGL : gl.RENDERER));
    const vendor = String(gl.getParameter(debug ? debug.UNMASKED_VENDOR_WEBGL : gl.VENDOR));
    document.getElementById('gl-value').textContent = renderer;
    emit('WEBGL_INFO', { renderer, vendor, version: String(gl.getParameter(gl.VERSION)), maxTexture: gl.getParameter(gl.MAX_TEXTURE_SIZE), supported: true });
    const lose = gl.getExtension('WEBGL_lose_context'); if (lose) lose.loseContext();
  } catch (_) {
    document.getElementById('gl-value').textContent = '接口不可用'; emit('FEATURE_UNAVAILABLE', { feature: 'webgl' });
  }
  (async function () {
    let pc;
    try {
      if (!window.RTCPeerConnection) throw new Error('unsupported');
      pc = new RTCPeerConnection({ iceServers: [] });
      const seen = new Set(); let privateIpCount = 0, mdnsCount = 0, done = false;
      function finish() {
        if (done) return; done = true;
        pc.close();
        document.getElementById('rtc-value').textContent = seen.size + ' 个本地候选 / 私网地址暴露 ' + privateIpCount;
        emit('WEBRTC_RESULT', { candidateCount: seen.size, privateIpCount, mdnsCount, supported: true });
      }
      pc.onicecandidate = function (event) {
        if (!event.candidate) { finish(); return; }
        const value = event.candidate.candidate;
        if (seen.has(value)) return; seen.add(value);
        if (/\.local\b/i.test(value)) mdnsCount++;
        if (/\b(?:10\.\d+\.\d+\.\d+|192\.168\.\d+\.\d+|172\.(?:1[6-9]|2\d|3[01])\.\d+\.\d+)\b/.test(value)) privateIpCount++;
      };
      pc.createDataChannel('local-diagnostic');
      await pc.setLocalDescription(await pc.createOffer());
      setTimeout(finish, 2500);
      addEventListener('pagehide', finish, { once: true });
    } catch (_) {
      if (pc) pc.close();
      document.getElementById('rtc-value').textContent = '接口不可用'; emit('FEATURE_UNAVAILABLE', { feature: 'webrtc' });
    }
  })();
  const viewport = window.visualViewport;
  let lastScale = viewport ? viewport.scale : 1;
  const reportViewport = throttle(function () {
    const width = Math.round(viewport ? viewport.width : innerWidth), height = Math.round(viewport ? viewport.height : innerHeight);
    const scale = viewport ? viewport.scale : 1;
    document.getElementById('viewport-value').textContent = width + ' × ' + height + ' / ' + scale.toFixed(2);
    emit('VIEWPORT_CHANGED', { width, height, scale });
    if (Math.abs(scale - lastScale) > 0.01) emit('WEB_ZOOM', { scale });
    lastScale = scale;
  }, 400);
  addEventListener('resize', reportViewport); if (viewport) viewport.addEventListener('resize', reportViewport); reportViewport();
  // A settled position keeps the live signal list from extending the page during a gesture.
  let scrollTimer;
  addEventListener('scroll', function () {
    clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () { emit('BROWSER_SCROLL', { scrollY: Math.round(scrollY) }); }, 350);
  }, { passive: true });
  addEventListener('focus', function () { emit('BROWSER_FOCUS', { focused: true }); });
  addEventListener('blur', function () { emit('BROWSER_FOCUS', { focused: false }); });
  document.addEventListener('visibilitychange', function () { emit('BROWSER_VISIBILITY', { visible: !document.hidden }); });
  document.addEventListener('fullscreenchange', function () { emit('BROWSER_FULL_SCREEN', { fullScreen: Boolean(document.fullscreenElement) }); });
  addEventListener('orientationchange', function () { emit('BROWSER_ORIENTATION', { orientation: screen.orientation ? screen.orientation.type : String(window.orientation) }); });
  document.addEventListener('copy', function () { emit('COPY', { len: String(getSelection()).length, method: 'copy-event' }); });
  document.addEventListener('paste', function (event) {
    const text = event.clipboardData ? event.clipboardData.getData('text/plain') : '';
    const decoded = SentryWatermark.decode(text), own = decoded === sid;
    const detail = { len: text.length, found: Boolean(decoded), matches: own };
    if (own) detail.wmSession = sid;
    emit('PASTE', detail);
  });
  document.addEventListener('selectionchange', throttle(function () {
    const len = String(getSelection()).length; if (len) emit('SELECTION', { len });
  }, 600));
  document.addEventListener('contextmenu', function () { emit('LONG_PRESS'); });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'F12' || ((event.ctrlKey || event.metaKey) && ((event.shiftKey && /^[ijc]$/i.test(event.key)) || /^u$/i.test(event.key)))) {
      emit('DEBUG_SHORTCUT', { shortcut: event.key === 'F12' ? 'F12' : 'Ctrl/Meta+' + (event.shiftKey ? 'Shift+' : '') + event.key.toUpperCase() });
    }
  });
  const peers = new Set();
  if (window.BroadcastChannel) {
    const channel = new BroadcastChannel('exam-sentry-' + sid);
    channel.onmessage = function (event) {
      const value = event.data;
      if (!value || value.sid !== sid || value.tab === tab || typeof value.tab !== 'string') return;
      if (!peers.has(value.tab)) {
        peers.add(value.tab); document.getElementById('peer-count').textContent = (peers.size + 1) + ' 个页面';
        emit('DUPLICATE_PAGE', { peerTab: value.tab.slice(0, 128) });
      }
      if (value.type === 'hello') channel.postMessage({ type: 'ack', sid, tab });
    };
    const hello = function () { channel.postMessage({ type: 'hello', sid, tab }); };
    hello(); const timer = setInterval(hello, 3000);
    addEventListener('pagehide', function () { clearInterval(timer); channel.close(); }, { once: true });
  } else emit('FEATURE_UNAVAILABLE', { feature: 'broadcast-channel' });
  let selected = null, changes = 0, firstAnswerMs = null;
  document.getElementById('answers').addEventListener('click', function (event) {
    const button = event.target.closest('[data-answer]'); if (!button) return;
    const changed = selected !== button.dataset.answer;
    selected = button.dataset.answer; if (changed) changes++;
    if (firstAnswerMs === null) firstAnswerMs = Math.round(performance.now() - started);
    document.querySelectorAll('[data-answer]').forEach(function (entry) { entry.classList.toggle('selected', entry === button); });
    document.getElementById('answer-state').textContent = '已选 ' + selected;
    emit('BROWSER_ANSWER', { answerChanges: changes, firstAnswerMs, changed });
  });
  document.getElementById('submit').addEventListener('click', function () {
    if (!selected) { document.getElementById('answer-state').textContent = '尚未选择'; return; }
    document.getElementById('answer-state').textContent = '实测已提交';
    emit('BROWSER_SUBMIT', { answerChanges: changes, firstAnswerMs, dwellMs: Math.round(performance.now() - started) });
  });
  const sample = document.getElementById('wm-sample'); sample.textContent += SentryWatermark.encode(sid);
  document.getElementById('copy-sample').addEventListener('click', async function () {
    const text = sample.textContent;
    try {
      if (!navigator.clipboard || !navigator.clipboard.writeText) throw new Error('fallback');
      await navigator.clipboard.writeText(text);
      emit('COPY', { len: text.length, method: 'clipboard-write-success' });
      document.getElementById('watermark-state').textContent = '水印样本已复制';
    } catch (_) {
      const selection = getSelection(), range = document.createRange(); range.selectNodeContents(sample);
      selection.removeAllRanges(); selection.addRange(range);
      const ok = document.execCommand('copy'); selection.removeAllRanges();
      document.getElementById('watermark-state').textContent = ok ? '水印样本已复制' : '复制接口不可用';
      if (!ok) emit('FEATURE_UNAVAILABLE', { feature: 'clipboard-write' });
    }
  });
  document.getElementById('check-watermark').addEventListener('click', function () {
    const input = document.getElementById('paste-area').value, decoded = SentryWatermark.decode(input), matches = decoded === sid;
    const detail = { len: input.length, found: Boolean(decoded), matches }; if (matches) detail.wmSession = sid;
    emit('WATERMARK_CHECK', detail);
    document.getElementById('watermark-state').textContent = matches ? '匹配本次会话水印' : (decoded ? '检测到其他会话水印' : '未发现完整水印');
  });
  document.getElementById('session-label').textContent = '会话 ' + sid.slice(0, 8) + ' · 页面 ' + tab.slice(0, 8);
  document.getElementById('page-state').textContent = window.SentryBridge ? '记录接口可用' : '本页预览';
  emit('BROWSER_READY', { supported: Boolean(window.SentryBridge) });
})();
