/* ============================================================
 * 算法动画 · 渲染器 + 播放器
 * ============================================================ */
(function () {

  function esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }

  /* ---------------- 通用面板：变量 ---------------- */
  function varsPanel(vars) {
    if (!vars) return '';
    var ks = Object.keys(vars);
    if (!ks.length) return '';
    return '<div class="vars">' + ks.map(function (k) {
      return '<span class="v"><i>' + esc(k) + '</i><b>' + esc(vars[k]) + '</b></span>';
    }).join('') + '</div>';
  }

  /* ---------------- 1. array：格子 + 指针 + 窗口 ---------------- */
  function rArray(f) {
    var arr = f.arr || [], hl = f.hl || [], ptr = f.ptr || {}, cls = f.cls || {};
    var win = f.win, n = arr.length;
    var h = '<div class="rw">';
    // 指针按所属格子分组
    var bySlot = {};
    Object.keys(ptr).forEach(function (name) {
      var i = ptr[name];
      if (i === null || i === undefined || i < 0) return;
      (bySlot[i] = bySlot[i] || []).push(name);
    });
    h += '<div class="cells">';
    for (var i2 = 0; i2 < n; i2++) {
      var c = [];
      if (hl.indexOf(i2) >= 0) c.push('hl');
      if (win && i2 >= win[0] && i2 <= win[1]) c.push('win');
      if (cls[i2]) c.push(cls[i2]);
      c.push('cell');
      var labels = (bySlot[i2] || []).map(function (nm) { return '<em class="pt p-' + nm + '">' + esc(nm) + '</em>'; }).join('');
      h += '<div class="' + c.join(' ') + '"><span class="idx">' + i2 + '</span><span class="val">' + esc(arr[i2]) + '</span><span class="plabels">' + labels + '</span></div>';
    }
    h += '</div>';

    // 指针条（下方，用绝对定位到格子）
    h += '<div class="ptrbar">';
    for (var i3 = 0; i3 < n; i3++) {
      var nm = (bySlot[i3] || []);
      h += '<div class="pslot">' + nm.map(function (x) { return '<em class="pt p-' + x + '">▲ ' + esc(x) + '</em>'; }).join('') + '</div>';
    }
    h += '</div>';

    if (f.map) {
      var mk = Object.keys(f.map);
      h += '<div class="subpanel"><span class="subt">哈希表</span>' +
        (mk.length ? mk.map(function (k) { return '<span class="kv"><i>' + esc(k) + '</i>→<b>' + esc(f.map[k]) + '</b></span>'; }).join('') : '<span class="empty">空</span>') + '</div>';
    }
    if (f.queue) {
      h += '<div class="subpanel"><span class="subt">双端队列（队首=最大）</span>' +
        (f.queue.length ? '<span class="qlist">' + f.queue.map(function (q) { return '<span class="qitem">' + esc(q.v) + '<i>#' + esc(q.sub.replace('#', '')) + '</i></span>'; }).join('') + '</span>' : '<span class="empty">空</span>') + '</div>';
    }
    if (f.res && f.res.length) {
      h += '<div class="subpanel"><span class="subt">已输出</span>' + f.res.map(function (v) { return '<span class="kv out">' + esc(v) + '</span>'; }).join('') + '</div>';
    }
    h += '</div>';
    return h;
  }

  /* ---------------- 2. bars：柱状图 ---------------- */
  function rBars(f) {
    var bars = f.bars || [], mx = 1;
    bars.forEach(function (b) { mx = Math.max(mx, Math.abs(b.v)); });
    var ptr = f.ptr || {};
    var h = '<div class="rw bars">';
    h += '<div class="barwrap" style="height:170px">';
    if (f.water && f.water[0] !== undefined && f.water[0] !== null) {
      var w0 = f.water[0], w1 = f.water[1], wh = f.water[2];
      var left = (w0 / bars.length * 100), width = ((w1 - w0) / bars.length * 100);
      var hgt = (wh / mx * 100);
      h += '<div class="water" style="left:' + left + '%;width:' + width + '%;height:' + hgt + '%"></div>';
    }
    bars.forEach(function (b, i) {
      var v = b.v, s = b.s || '';
      var hh = Math.max(3, Math.abs(v) / mx * 100);
      var neg = v < 0;
      h += '<div class="barcol"><div class="barbox"><div class="bar ' + s + (neg ? ' neg' : '') + '" style="height:' + hh + '%"></div></div>' +
        '<span class="bval ' + s + '">' + esc(v) + '</span>' +
        '<span class="bidx">' + i + '</span>' +
        (Object.keys(ptr).filter(function (k) { return ptr[k] === i; }).map(function (k) { return '<em class="pt p-' + k + '">▲' + esc(k) + '</em>'; }).join('')) +
        '</div>';
    });
    h += '</div>';
    if (f.stack) {
      h += '<div class="subpanel"><span class="subt">' + esc(f.stackLabel || '栈') + '</span>' +
        (f.stack.length ? f.stack.map(function (s) { return '<span class="kv' + (s.s ? ' ' + s.s : '') + '">' + esc(s.t) + '</span>'; }).join('') : '<span class="empty">空</span>') + '</div>';
    }
    h += '</div>';
    return h;
  }

  /* ---------------- 3. list：链表 ---------------- */
  function rList(f) {
    var vals = f.vals || [], next = f.next || [], ptr = f.ptr || {}, hl = f.hl || [];
    var n = vals.length, W = 84, GAP = 22, X0 = 40, Y = 74;
    var dummy = f.dummy;
    var total = n + (dummy ? 1 : 0);
    var svgW = X0 * 2 + total * W + (total - 1) * GAP;
    var h = '<div class="rw"><svg viewBox="0 0 ' + svgW + ' 150" class="lsvg">';
    function cx(i) { return X0 + (dummy ? i + 1 : i) * (W + GAP); }
    // 节点
    function node(i, label, sub) {
      var x = cx(i), c = 'nbox';
      if (hl.indexOf(i) >= 0) c += ' hl';
      h += '<g class="' + c + '"><rect x="' + x + '" y="' + (Y - 20) + '" width="' + W + '" height="40" rx="6"/>' +
        '<text class="nv" x="' + (x + W / 2) + '" y="' + (Y + 6) + '">' + esc(label) + '</text>' +
        (sub ? '<text class="nid" x="' + (x + W / 2) + '" y="' + (Y - 26) + '">' + esc(sub) + '</text>' : '') + '</g>';
    }
    if (dummy) {
      h += '<g class="nbox dummy"><rect x="8" y="' + (Y - 16) + '" width="54" height="32" rx="6"/><text class="nv" x="35" y="' + (Y + 5) + '">dummy</text></g>';
      h += '<path class="lnk" d="M62,' + Y + ' L' + (cx(0) - 12) + ',' + Y + '" marker-end="url(#ar)"/>';
    }
    for (var i = 0; i < n; i++) node(i, vals[i], '#' + i);
    // 链接
    function arrow(fromX, fromY, toX, toY, dead) {
      var c = dead ? 'lnk dead' : 'lnk';
      h += '<path class="' + c + '" d="M' + fromX + ',' + fromY + ' L' + toX + ',' + toY + '" marker-end="url(#ar' + (dead ? '2' : '') + ')"/>';
    }
    h += '<defs><marker id="ar" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" class="arh"/></marker>' +
      '<marker id="ar2" markerWidth="8" markerHeight="8" refX="7" refY="3" orient="auto"><path d="M0,0 L7,3 L0,6 z" class="arh dead"/></marker></defs>';
    // 先画 defs 再画 path 无效（defs 要在前面），这里重新组织：defs 已插入到 h 中，marker 引用仍然有效（SVG 允许后定义）
    for (var j = 0; j < n; j++) {
      var tgt = next[j];
      var sx = cx(j) + W, sy = Y;
      if (tgt === -1) {
        h += '<path class="lnk" d="M' + sx + ',' + sy + ' L' + (sx + 46) + ',' + sy + '" marker-end="url(#ar)"/>';
        h += '<text class="nullt" x="' + (sx + 24) + '" y="' + (sy - 8) + '">null</text>';
      } else if (tgt >= 0) {
        var tx = cx(tgt);
        if (tx > sx) {
          h += '<path class="lnk" d="M' + sx + ',' + sy + ' L' + (tx - 12) + ',' + sy + '" marker-end="url(#ar)"/>';
        } else {
          // 反向：走下方绕行
          h += '<path class="lnk rev" d="M' + (sx) + ',' + sy + ' C' + (sx + 30) + ',' + (sy + 46) + ' ' + (tx + W / 2) + ',' + (sy + 46) + ' ' + (tx + W / 2) + ',' + (sy + 24) + '" marker-end="url(#ar)"/>';
        }
      }
    }
    // 指针标签
    var bySlot = {};
    Object.keys(ptr).forEach(function (nm) {
      var v = ptr[nm];
      if (v === null || v === undefined) return;
      (bySlot[v] = bySlot[v] || []).push(nm);
    });
    Object.keys(bySlot).forEach(function (slot) {
      var s = Number(slot);
      var x = s === -2 ? 35 : (s < 0 ? cx(0) : cx(s) + W / 2);
      bySlot[slot].forEach(function (nm, k) {
        h += '<text class="plab p-' + nm + '" x="' + x + '" y="' + (30 + k * 15) + '">' + esc(nm) + '</text>';
        h += '<path class="pln p-' + nm + '" d="M' + x + ',' + (34 + k * 15) + ' L' + x + ',' + (Y - 30) + '" marker-end="url(#ar)"/>';
      });
    });
    h += '</svg></div>';
    return h;
  }

  /* ---------------- 4. stack：栈 ---------------- */
  function rStack(f) {
    var st = f.stack || [], h = '<div class="rw stackrw">';
    h += '<div class="stcol">';
    h += '<div class="stlabel">栈（顶→底）</div>';
    h += '<div class="stbox">';
    if (!st.length) h += '<div class="stempty">空</div>';
    st.slice().reverse().forEach(function (it, k) {
      var isTop = k === 0;
      var cls = 'stitem' + (isTop ? ' top' : '') + (f.action === 'pop' && isTop ? ' popping' : '') + (f.action === 'push' && isTop ? ' pushing' : '') + (f.action === 'fail' && isTop ? ' bad' : '');
      h += '<div class="' + cls + '"><span>' + esc(typeof it === 'object' ? it.v : it) + '</span>' + (typeof it === 'object' && it.sub ? '<i>' + esc(it.sub) + '</i>' : '') + '</div>';
    });
    h += '</div></div>';
    if (f.incoming) {
      h += '<div class="incoming ' + (f.action === 'push' ? 'act' : (f.action === 'fail' ? 'bad' : '')) + '"><span class="subt">待处理</span><div class="inc">' + esc(f.incoming) + '</div></div>';
    }
    h += '</div>';
    if (f.bars) {
      var mx = Math.max.apply(null, f.bars);
      h += '<div class="subpanel wide"><span class="subt">温度序列</span><div class="tlist">';
      f.bars.forEach(function (v, i) {
        h += '<div class="tcol' + (i === f.cur ? ' cur' : '') + '"><div class="tb" style="height:' + (v / mx * 40) + 'px"></div><span>' + esc(v) + '</span><i>' + (f.ans && f.ans[i] !== undefined ? f.ans[i] : '·') + '</i></div>';
      });
      h += '</div><div class="hint">柱下数字=温度，斜体=答案（还需等几天）</div></div>';
    }
    return h;
  }

  /* ---------------- 5. dp：表格 ---------------- */
  function rDp(f) {
    var rows = f.rows, rl = f.rl, cl = f.cl, cur = f.cur, src = f.src || [];
    var inSrc = {};
    src.forEach(function (p) { inSrc[p[0] + ',' + p[1]] = 1; });
    var h = '<div class="rw"><table class="dpt">';
    h += '<tr><th class="corner"></th>' + cl.map(function (c, j) { return '<th' + (cur && cur[1] === j ? ' class="ch"' : '') + '>' + esc(c) + '</th>'; }).join('') + '</tr>';
    for (var i = 0; i < rows.length; i++) {
      h += '<tr><th' + (cur && cur[0] === i ? ' class="ch"' : '') + '>' + esc(rl[i]) + '</th>';
      for (var j = 0; j < rows[i].length; j++) {
        var c = 'td';
        if (cur && cur[0] === i && cur[1] === j) c += ' cur';
        if (inSrc[i + ',' + j]) c += ' src';
        h += '<td class="' + c + '">' + esc(rows[i][j]) + '</td>';
      }
      h += '</tr>';
    }
    h += '</table><div class="legend"><span class="lg cur"></span>正在计算 <span class="lg src"></span>依赖来源</div></div>';
    return h;
  }

  /* ---------------- 6. grid：二维网格 ---------------- */
  function rGrid(f) {
    var g = f.grid, R = g.length, C = g[0].length, h = '<div class="rw"><div class="gridbox" style="grid-template-columns:repeat(' + C + ',1fr);max-width:' + (C * 46) + 'px">';
    var cur = f.cur, flood = f.flood || [], mk = f.mark;
    var fs = {};
    flood.forEach(function (p) { fs[p[0] + ',' + p[1]] = 1; });
    for (var i = 0; i < R; i++) {
      for (var j = 0; j < C; j++) {
        var c = 'gc';
        if (g[i][j]) c += ' land'; else c += ' water';
        if (cur && cur[0] === i && cur[1] === j) c += ' cur';
        if (fs[i + ',' + j]) c += ' flood';
        var txt = '';
        if (f.n !== undefined) {
          if (mk && mk.r === i && mk.c === j && mk.t === 'x') { txt = '✕'; c += ' conflict'; }
          else if (g[i][j]) { txt = '♛'; c += ' queen'; }
        }
        h += '<div class="' + c + '">' + txt + '</div>';
      }
    }
    h += '</div>';
    if (f.n !== undefined) h += '<div class="legend"><span class="lg queen"></span>皇后 <span class="lg conflict"></span>冲突位置 &nbsp;|&nbsp; 已找到解：<b>' + (f.sols || 0) + '</b></div>';
    else h += '<div class="legend"><span class="lg land"></span>陆地 <span class="lg water"></span>海洋/已淹没 <span class="lg cur"></span>当前格子</div>';
    h += '</div>';
    return h;
  }

  /* ---------------- 7. tree：树 ---------------- */
  function rTree(f) {
    var nodes = f.nodes, edges = f.edges || [], W = f.W, H = f.H;
    var vw = 560, vh = 250;
    function px(x) { return 40 + x / W * (vw - 80); }
    function py(y) { return 34 + y / Math.max(1, H - 1) * (vh - 78); }
    var pos = {};
    nodes.forEach(function (n) { pos[n.id] = { x: px(n.x), y: py(n.y) }; });
    var h = '<div class="rw"><svg viewBox="0 0 ' + vw + ' ' + vh + '" class="tsvg">';
    edges.forEach(function (e) {
      var a = pos[e[0]], b = pos[e[1]];
      if (!a || !b) return;
      h += '<line class="tedge" x1="' + a.x + '" y1="' + a.y + '" x2="' + b.x + '" y2="' + b.y + '"/>';
    });
    nodes.forEach(function (n) {
      var p = pos[n.id];
      h += '<circle class="tnode ' + (n.s || '') + '" cx="' + p.x + '" cy="' + p.y + '" r="17"/>';
      h += '<text class="tv" x="' + p.x + '" y="' + (p.y + 5) + '">' + esc(n.v) + '</text>';
    });
    h += '</svg>';
    if (f.queue) h += '<div class="subpanel"><span class="subt">队列</span>' + (f.queue.length ? f.queue.map(function (v) { return '<span class="kv out">' + esc(v) + '</span>'; }).join('') : '<span class="empty">空</span>') + '</div>';
    if (f.result && f.result.length) h += '<div class="subpanel"><span class="subt">已输出层级</span>' + f.result.map(function (lv) { return '<span class="kv out">[' + lv.join(' ') + ']</span>'; }).join('') + '</div>';
    h += '</div>';
    return h;
  }

  /* ---------------- 8. graph：图 ---------------- */
  function rGraph(f) {
    var nodes = f.nodes, edges = f.edges || [];
    var vw = 560, vh = 260;
    var pos = {};
    nodes.forEach(function (n) { pos[n.id] = { x: n.x / 100 * (vw - 80) + 40, y: n.y / 100 * (vh - 70) + 35 }; });
    var h = '<div class="rw"><svg viewBox="0 0 ' + vw + ' ' + vh + '" class="gsvg">';
    h += '<defs><marker id="gar" markerWidth="9" markerHeight="9" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6 z" class="arh"/></marker></defs>';
    edges.forEach(function (e) {
      var a = pos[e.a], b = pos[e.b];
      if (!a || !b) return;
      var dx = b.x - a.x, dy = b.y - a.y, len = Math.sqrt(dx * dx + dy * dy);
      if (!len) return;
      var ux = dx / len, uy = dy / len, ox = 19;
      var sx = a.x + ux * ox, sy = a.y + uy * ox, ex = b.x - ux * ox, ey = b.y - uy * ox;
      var mx = (sx + ex) / 2 - uy * 14, my = (sy + ey) / 2 + ux * 14;
      h += '<path class="gedge ' + (e.s || '') + '" d="M' + sx + ',' + sy + ' Q' + mx + ',' + my + ' ' + ex + ',' + ey + '" marker-end="url(#gar)"/>';
      if (e.w !== undefined) h += '<text class="ew" x="' + mx + '" y="' + (my - 4) + '">' + esc(e.w) + '</text>';
    });
    nodes.forEach(function (n) {
      var p = pos[n.id];
      h += '<circle class="gnode ' + (n.s || '') + '" cx="' + p.x + '" cy="' + p.y + '" r="19"/>';
      h += '<text class="gv" x="' + p.x + '" y="' + (p.y + 5) + '">' + esc(n.id) + '</text>';
      var lab = n.dist !== undefined ? (n.dist === null || n.dist === Infinity || n.dist > 1e9 ? '∞' : n.dist)
        : (n.indeg !== undefined ? 'in ' + n.indeg : (n.tag !== undefined ? n.tag : ''));
      if (lab !== '') h += '<text class="gd" x="' + p.x + '" y="' + (p.y + 33) + '">' + esc(lab) + '</text>';
    });
    h += '</svg>';
    if (f.queue) h += '<div class="subpanel"><span class="subt">就绪队列</span>' + (f.queue.length ? f.queue.map(function (v) { return '<span class="kv out">' + esc(v) + '</span>'; }).join('') : '<span class="empty">空</span>') + '</div>';
    h += '</div>';
    return h;
  }

  var R = { array: rArray, bars: rBars, list: rList, stack: rStack, dp: rDp, grid: rGrid, tree: rTree, graph: rGraph };
  window.ALGO_RENDER = R;

  /* ============================================================
   * 播放器
   * ============================================================ */
  function Player(els) {
    this.els = els;
    this.frames = []; this.i = 0; this.timer = null; this.speed = 900;
    this.demo = null;
  }
  Player.prototype.load = function (demo, inputText) {
    this.stop();
    this.demo = demo;
    var inp;
    try { inp = demo.parse(inputText); }
    catch (e) { this.els.canvas.innerHTML = '<div class="err">输入解析失败：' + esc(e.message) + '</div>'; return; }
    try { this.frames = demo.run(inp); }
    catch (e) { this.els.canvas.innerHTML = '<div class="err">运行出错：' + esc(e.message) + '</div>'; return; }
    this.i = 0;
    this.render();
  };
  Player.prototype.render = function () {
    var f = this.frames[this.i], d = this.demo;
    var fn = R[d.render] || rArray;
    this.els.canvas.innerHTML = fn(f);
    this.els.note.innerHTML = '<span class="stepno">' + (this.i + 1) + '</span>' + esc(f.note || '');
    this.els.vars.innerHTML = varsPanel(f.vars);
    this.els.slider.max = this.frames.length - 1;
    this.els.slider.value = this.i;
    this.els.counter.textContent = (this.i + 1) + ' / ' + this.frames.length;
  };
  Player.prototype.go = function (i) {
    if (i < 0) i = 0;
    if (i > this.frames.length - 1) { i = this.frames.length - 1; this.stop(); }
    this.i = i; this.render();
  };
  Player.prototype.next = function () { if (this.i < this.frames.length - 1) this.go(this.i + 1); else this.stop(); };
  Player.prototype.prev = function () { this.go(this.i - 1); };
  Player.prototype.play = function () {
    if (this.i >= this.frames.length - 1) this.i = 0;
    var self = this; this.stop();
    this.els.play.textContent = '⏸ 暂停';
    this.timer = setInterval(function () {
      if (self.i >= self.frames.length - 1) { self.stop(); return; }
      self.go(self.i + 1);
    }, this.speed);
  };
  Player.prototype.stop = function () {
    if (this.timer) { clearInterval(this.timer); this.timer = null; }
    if (this.els.play) this.els.play.textContent = '▶ 播放';
  };
  Player.prototype.toggle = function () { this.timer ? this.stop() : this.play(); };
  window.AlgoPlayer = Player;

})();
