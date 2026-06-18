// Injected by the Android wrapper at document-start. Forwards the player's
// now-playing info to the native foreground service, lets the native
// notification / lock-screen controls drive the page, and injects corrective
// MOBILE CSS over the live page. Does NOT modify the served page.
(function () {
  // --- Mobile layout & touch fixes ----------------------------------------
  // player.html only restyles the top bar + Player tab for phones. This sheet
  // gives every view/overlay a phone layout and makes touch first-class.
  // Round 2 adds a full mobile redesign of the LIBRARY browser (reordered via
  // flex `order`, restyled — the page's markup is never edited). All rules are
  // scoped to @media(max-width:760px) or @media(hover:none); only the Android
  // app ships this file. Idempotent. Uses !important deliberately: it must
  // beat both the page's own styles (document order isn't guaranteed at
  // document-start) and inline styles set by the page's JS (popToggle()
  // positions .pop with element.style.top/right).
  var MOBILE_CSS = [
    '@media (max-width:760px){',
    ':root{--ms-bar-h:162px}',

    /* ----- top bar: wrap the icon row, 44px tap targets ----- */
    '.top{flex-wrap:wrap;gap:8px;padding:10px 12px}',
    '.top .right{flex-wrap:wrap;justify-content:flex-end;width:100%;margin-left:0;gap:8px}',
    '#cast-pill{flex:1 1 100%}',
    '#cast-pill select{max-width:none;flex:1}',
    '.ibtn{width:44px;height:44px;font-size:17px}',
    '.seg button{padding:10px 16px;font-size:13.5px}',
    '.search{padding:12px 16px;font-size:15px}',
    '.results{width:100% !important;max-height:60vh}',

    /* ----- natural page scroll instead of a clipped full-height stage ----- */
    '.stage{height:auto !important;overflow:visible !important;padding:4px 12px 14px}',
    'body{overflow-y:auto;overflow-x:hidden;' +
      'padding-bottom:calc(var(--ms-bar-h) + 22px + env(safe-area-inset-bottom));' +
      '-webkit-tap-highlight-color:transparent}',
    '.bento{gap:10px}',
    '.tile{padding:14px}',

    /* ----- pinned bottom transport (native pattern; page lets it scroll away) ----- */
    /* If this section misbehaves, delete it AND the body padding-bottom above. */
    '.bar{position:fixed;left:0;right:0;bottom:0;z-index:290;height:auto !important;' +
      'grid-template-columns:1fr !important;grid-template-rows:auto auto;gap:6px;' +
      'padding:10px 14px calc(12px + env(safe-area-inset-bottom)) !important}',
    '.bar-right{display:none !important}',
    '.bar-np img{width:44px;height:44px}',
    '.bar-np .s{display:block !important}',
    '.bar-np .bar-rate{gap:9px}',
    '.bar-np .bar-rate .stars{font-size:17px;gap:4px}',
    '.bar-np .bar-rate .heart{font-size:18px}',
    '.center{width:100%}',
    '.tbtns{gap:26px}',
    '.seekrow{max-width:none !important}',
    '.seek{height:32px}',
    '#toast{bottom:calc(var(--ms-bar-h) + 24px + env(safe-area-inset-bottom)) !important}',
    '.follow-banner.show{position:fixed;left:0;right:0;' +
      'bottom:calc(var(--ms-bar-h) + env(safe-area-inset-bottom));z-index:291}',
    'html.__ms-kb .bar{display:none}',

    /* ================= LIBRARY — mobile redesign =================
       New top-to-bottom flow (reordered with flex `order`):
       [tabs] [search + collapsible lyrics search] [filter chip rail]
       [quick-picks rail] [summary] [back-chips] [content]            */
    '#view-library.libview.show{flex-direction:column;height:auto;gap:10px}',
    '#view-library .lib-qpanel{display:none}',
    '#view-library .lib-browse{min-height:0;width:100%}',

    /* header order */
    '#view-library .lib-row1{order:0;gap:8px;margin-bottom:0}',
    '#view-library .lib-row2{order:1}',
    '#view-library .lib-quick{order:2}',
    '#view-library .lib-summary{order:3}',
    '#view-library .lib-crumbs{order:4}',
    '#view-library .lib-main{order:5}',

    /* 1) Artists | Albums | Songs -> full-width segmented tabs */
    '#view-library .lib-target{flex:1 1 100%;display:flex;padding:4px}',
    '#view-library .lib-target button{flex:1;padding:12px 0;font-size:14px}',

    /* 2) one search row; lyrics search collapses to a 🎤 button,
          expands to a full bar on tap (and stays open while it has a query) */
    '#view-library .lib-search{margin-left:0;min-width:0;max-width:none;flex:1 1 auto;' +
      'height:46px;padding:0 14px}',
    '#view-library .lib-search input{font-size:14px}',
    '#view-library .lib-lyrsearch{flex:0 0 46px;width:46px;justify-content:center;' +
      'padding:0;cursor:pointer}',
    '#view-library .lib-lyrsearch .si{font-size:17px;opacity:.9}',
    '#view-library .lib-lyrsearch input{width:0;min-width:0;flex:0 0 0px;opacity:0;padding:0}',
    '#view-library .lib-lyrsearch:not(.has-q):not(:focus-within) .sx{display:none}',
    '#view-library .lib-lyrsearch:focus-within,#view-library .lib-lyrsearch.has-q{' +
      'flex:1 1 100%;width:auto;justify-content:flex-start;padding:0 14px;cursor:text}',
    '#view-library .lib-lyrsearch:focus-within input,#view-library .lib-lyrsearch.has-q input{' +
      'width:auto;flex:1 1 auto;opacity:1}',

    /* 3) filters -> one horizontally scrolling chip rail (no wrapping) */
    '#view-library .lib-row2{display:flex;flex-wrap:nowrap;overflow-x:auto;gap:7px;' +
      'margin-bottom:0;padding:2px 2px 6px;-webkit-overflow-scrolling:touch;scrollbar-width:none}',
    '#view-library .lib-row2::-webkit-scrollbar{display:none}',
    '#view-library .lib-row2>*{flex:0 0 auto}',
    '#view-library .focus-lbl{display:none}',
    '#view-library #lib-addmode{order:-1;margin-left:0}',     /* queue-building first */
    '#view-library .facet,#view-library .facet-clear{padding:10px 15px;font-size:13px}',
    '#view-library .lib-sort{padding:10px 14px;font-size:13px}',
    '#view-library .lib-addmode{padding:10px 15px;font-size:13px}',

    /* 4) quick picks: moved up from below the whole list (unreachable) to a
          second rail under the filters */
    '#view-library .lib-quick{display:none;flex-wrap:nowrap;overflow-x:auto;gap:7px;margin-top:0;' +
      'padding:0 2px 6px;border-top:none;-webkit-overflow-scrolling:touch;scrollbar-width:none}',
    '#view-library .lib-quick::-webkit-scrollbar{display:none}',
    '#view-library .lib-quick button{flex:0 0 auto;padding:9px 15px;font-size:12.5px}',
    '#view-library .qp-lbl{display:none}',

    /* 5) summary + crumbs: compact, gone entirely when empty */
    '#view-library .lib-summary{min-height:0;margin-bottom:4px}',
    '#view-library .lib-summary:empty{display:none}',
    '#view-library .lib-crumbs{min-height:0;margin-bottom:8px;gap:6px}',
    '#view-library .lib-crumbs:empty{display:none}',
    '#view-library .lib-crumbs a{background:var(--tile2);border:1px solid var(--border);' +
      'color:var(--text);padding:8px 13px;border-radius:999px;font-size:13px;text-decoration:none}',
    '#view-library .lib-crumbs .sepc{display:none}',

    /* 6) content */
    '#view-library .lib-body{overflow:visible;padding-right:0}',
    '#view-library .lib-main{margin-top:2px}',
    /* artist list -> native list rows: taller, hairline dividers, count pill */
    '.alist .arow{padding:13px 10px;border-radius:0}',
    '.alist .arow+.arow{border-top:1px solid color-mix(in srgb,var(--border) 55%,transparent)}',
    '.arow .an{font-size:15px}',
    '.arow .ac{font-size:12px;background:var(--tile2);border-radius:999px;padding:3px 9px}',
    '.arow .av{font-size:16px}',
    /* album grid + album header */
    '.albgrid{grid-template-columns:repeat(auto-fill,minmax(108px,1fr));gap:12px}',
    '.albhead{flex-wrap:wrap;gap:12px}',
    '.albhead img{width:84px;height:84px}',
    '.albhead .at{font-size:18px}',
    /* Play / Shuffle / Add-all -> scrollable action rail (title dropped:
       the summary/crumbs already say where you are) */
    '#view-library .lvlbar{flex-wrap:nowrap;overflow-x:auto;gap:8px;margin-bottom:12px;' +
      'padding-bottom:4px;-webkit-overflow-scrolling:touch;scrollbar-width:none}',
    '#view-library .lvlbar::-webkit-scrollbar{display:none}',
    '#view-library .lvlbtn{flex:0 0 auto;padding:11px 18px;font-size:13px}',
    '#view-library .lvltitle{display:none}',
    /* song rows */
    '.ltrack{padding:10px 9px}',
    /* A–Z jumper -> floating fast-scroll rail on the right edge
       (was stretching across the full list height once the page scrolled) */
    '#view-library .az-rail{position:fixed;right:3px;top:45%;transform:translateY(-50%);' +
      'z-index:285;background:color-mix(in srgb,var(--bg2) 86%,transparent);' +
      'border:1px solid var(--border);border-radius:999px;padding:7px 1px;' +
      'backdrop-filter:blur(8px);max-height:56vh;overflow:hidden;justify-content:center}',
    '#view-library .az-rail.hidden{display:none}',
    '#view-library .az-rail span{font-size:10.5px;line-height:1.3;padding:0 6px}',
    '#view-library .lib-main:has(.az-rail:not(.hidden)) .lib-body{padding-right:24px}',
    /* ================= end LIBRARY redesign ================= */

    /* ----- PLAYLISTS ----- */
    '#view-playlists.show{height:auto}',
    '.plview{padding-right:0}',
    '.grid{grid-template-columns:repeat(auto-fill,minmax(124px,1fr));gap:14px}',

    /* ----- popovers (EQ, sleep, genre/decade facets) -> bottom sheets ----- */
    '.pop{left:10px !important;right:10px !important;top:auto !important;' +
      'bottom:calc(10px + env(safe-area-inset-bottom)) !important;width:auto !important;' +
      'max-height:72vh;overflow-y:auto;border-radius:18px;' +
      'box-shadow:0 -18px 60px rgba(0,0,0,.65)}',
    '.pop.show::before{content:"";display:block;width:38px;height:4px;border-radius:2px;' +
      'background:var(--border);margin:-4px auto 12px}',
    '.pop input[type=range]{height:30px}',
    '.chip{padding:10px 15px;font-size:13px}',
    '.facet-opts .fo{padding:12px 11px;font-size:14px}',

    /* ----- full-screen overlays ----- */
    '.hist-box{width:100% !important;height:100% !important;max-height:none;' +
      'border-radius:0 !important;padding:14px 14px calc(14px + env(safe-area-inset-bottom))}',
    '.help-overlay{padding:0 !important}',
    '.help-card{width:100% !important;height:100%;max-height:none !important;border-radius:0 !important}',
    '.help-grid{grid-template-columns:1fr !important}',
    '.help-body{padding:6px 16px 28px}',
    '.fb-box{width:calc(100vw - 20px) !important;max-height:88vh;overflow-y:auto;padding:20px}',
    '.qg-box{margin-top:7vh !important;width:calc(100vw - 20px) !important;padding:20px}',
    '.warn-box{max-width:calc(100vw - 28px)}',
    '.viz .close{top:10px;right:10px;padding:12px;font-size:28px}',

    /* ----- long-press context menu: comfortable touch rows ----- */
    '#ctxmenu{min-width:200px}',
    '#ctxmenu button{padding:13px 14px;font-size:14px}',
    '#ctxmenu .submenu button{padding:12px 14px}',

    /* ----- list rows: bigger tap targets ----- */
    '.qrow{padding:10px 8px}',
    '.res-row{padding:12px 13px}',

    /* ----- queue tile: hug its content, scroll past ~7 rows (was a huge fixed box) ----- */
    '.qtile{min-height:0 !important}',
    '#view-player .qtile .scroll{max-height:min(400px,52vh);overflow-y:auto}',
    '}',

    /* ===== touch devices (any width): hover has no meaning ===== */
    '@media (hover:none){',
    '.qx,.qx2,.ltrack .la{opacity:1}',
    '.qx{font-size:17px;padding:6px 10px}',
    '.qx2{font-size:15px;padding:6px 9px}',
    '.ltrack .la{font-size:16px;padding:8px}',
    '.res-add,.res-next{padding:8px 9px}',
    '.qrow,.ltrack,.res-row,.arow,.albcard,.pcard,.facet-opts .fo,.ibtn,.chip,.facet,' +
      '.seg button,.qhtab,#ctxmenu button{-webkit-user-select:none;user-select:none;' +
      '-webkit-touch-callout:none}',
    /* the ⠿ grip is the touch drag-handle for queue reorder (pointer-drag, 2026-06-11) — keep it big */
    '.qgrip{display:block;font-size:18px;padding:10px 6px}',
    '}'
  ].join('');

  function injectMobileCss() {
    try {
      var st = document.getElementById('__msMobileCss');
      if (!st) {
        st = document.createElement('style');
        st.id = '__msMobileCss';
        st.textContent = MOBILE_CSS;
      }
      // (Re)append so this sheet sits LAST in <head> and wins the cascade —
      // at document-start it can otherwise land before the page's own styles.
      (document.head || document.documentElement).appendChild(st);
    } catch (e) {}
  }
  injectMobileCss();
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', injectMobileCss);
  }

  // Hide the pinned transport while the on-screen keyboard is up.
  try {
    document.addEventListener('focusin', function (e) {
      var t = e.target && e.target.tagName;
      if (t === 'INPUT' || t === 'TEXTAREA') {
        document.documentElement.classList.add('__ms-kb');
      }
    });
    document.addEventListener('focusout', function () {
      document.documentElement.classList.remove('__ms-kb');
    });
  } catch (e) {}

  // Tapping the collapsed 🎤 lyrics-search button focuses its input, which
  // expands it (the CSS keys off :focus-within / .has-q).
  try {
    document.addEventListener('click', function (e) {
      if (!e.target || !e.target.closest) return;
      var box = e.target.closest('.lib-lyrsearch');
      if (!box) return;
      var inp = box.querySelector('input');
      if (inp && document.activeElement !== inp) {
        try { inp.focus(); } catch (err) {}
      }
    });
  } catch (e) {}

  if (window.__msAndroid) return;

  // --- Adaptive bottom-bar height -----------------------------------------
  // --ms-bar-h reserves scroll space above the pinned transport and positions
  // the toast / follow-banner. A hardcoded guess (162px) is wrong on a phone
  // whose width makes the bar wrap to a different height (e.g. a wider screen
  // keeps now-playing on one line → shorter bar → dead gap; a narrower one wraps
  // → taller bar → content hidden behind it). Measure the real bar instead, so
  // every screen gets the correct layout automatically.
  function syncBarHeight() {
    try {
      if (window.innerWidth > 760) return;            // mobile layout only
      var bar = document.querySelector('.bar');
      if (!bar) return;
      var h = Math.round(bar.getBoundingClientRect().height);
      if (h > 0) document.documentElement.style.setProperty('--ms-bar-h', h + 'px');
    } catch (e) {}
  }
  try {
    syncBarHeight();
    window.addEventListener('resize', syncBarHeight);
    window.addEventListener('orientationchange', function () { setTimeout(syncBarHeight, 250); });
    var __bar = document.querySelector('.bar');
    if (__bar && window.ResizeObserver) { new ResizeObserver(syncBarHeight).observe(__bar); }
  } catch (e) {}

  var handlers = {};          // captured navigator.mediaSession action handlers
  var last = { title: '', artist: '', art: '', playing: false };

  // Capture the page's media action handlers (runs before page scripts set them).
  try {
    var ms = navigator.mediaSession;
    if (ms && typeof ms.setActionHandler === 'function') {
      var orig = ms.setActionHandler.bind(ms);
      ms.setActionHandler = function (action, cb) {
        handlers[action] = cb;
        try { return orig(action, cb); } catch (e) { return undefined; }
      };
    }
  } catch (e) {}

  var ACTION_MAP = { play: 'play', pause: 'pause', next: 'nexttrack', prev: 'previoustrack' };

  function findAudio(playingOnly) {
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) {
      if (!playingOnly || !list[i].paused) return list[i];
    }
    return list.length ? list[0] : null;
  }

  // Called from native (notification / lock-screen buttons).
  window.__msAndroid = {
    action: function (a) {
      // Preferred: the player page's own transport hook (works even though the
      // WebView has no Media Session API — fixes dead car/lock-screen buttons).
      if (typeof window.__npAction === 'function') { try { window.__npAction(a); return; } catch (e) {} }
      var key = ACTION_MAP[a] || a;
      if (handlers[key]) { try { handlers[key](); return; } catch (e) {} }
      // Fallback: drive the audio element directly.
      var au = findAudio(false);
      if (!au) return;
      if (a === 'play') { try { au.play(); } catch (e) {} }
      else if (a === 'pause') { try { au.pause(); } catch (e) {} }
    }
  };

  function pushState() {
    var playing = false;
    var list = document.querySelectorAll('audio');
    for (var i = 0; i < list.length; i++) { if (!list[i].paused) playing = true; }

    var title = '', artist = '', art = '';
    try {
      // Preferred: the page's direct now-playing hand-off (the WebView has no
      // Media Session API, so navigator.mediaSession is usually absent here —
      // this was why the car showed "unknown unknown").
      var np = window.__np;
      if (np && (np.title || np.artist)) {
        title = np.title || '';
        artist = np.artist || '';
        art = np.art || '';
      } else {
        var md = navigator.mediaSession && navigator.mediaSession.metadata;
        if (md) {
          title = md.title || '';
          artist = md.artist || '';
          if (md.artwork && md.artwork.length) art = md.artwork[md.artwork.length - 1].src || '';
        }
      }
    } catch (e) {}

    if (title !== last.title || artist !== last.artist || playing !== last.playing || art !== last.art) {
      last = { title: title, artist: artist, art: art, playing: playing };
      try {
        if (window.AndroidBridge && AndroidBridge.updateNowPlaying) {
          if (title || playing) {
            AndroidBridge.updateNowPlaying(title || 'MediaSage', artist || '', art || '', !!playing);
          }
        }
      } catch (e) {}
    }
  }

  setInterval(function () { pushState(); syncBarHeight(); }, 1500);
})();
