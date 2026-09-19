/* ============================================================
 * 算法动画 · 演示定义（第三批）
 * 覆盖前两批没做的模板：设计·数据结构（LRU / Trie）、回溯决策树、
 * 状态机 DP、并查集、单调栈、BST、01 背包、Floyd 判环
 * 帧结构：{ note, vars, ...渲染器字段 }
 * ============================================================ */
window.ALGO_DEMOS = window.ALGO_DEMOS || [];

(function () {
  var D = window.ALGO_DEMOS;

  /* ---------- 通用小工具 ---------- */
  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  /* 通用树布局：叶子按 DFS 顺序占 x，内部节点取子节点均值 */
  function tidyLayout(nodes, parentOf) {
    var kids = {}, X = {}, Y = {}, counter = 0, maxD = 0;
    nodes.forEach(function (n) { kids[n.id] = []; });
    nodes.forEach(function (n) { if (parentOf[n.id] !== null && parentOf[n.id] !== undefined) kids[parentOf[n.id]].push(n.id); });
    function dfs(id, d) {
      Y[id] = d; if (d > maxD) maxD = d;
      if (!kids[id].length) { X[id] = counter++; return; }
      var s = 0;
      kids[id].forEach(function (c) { dfs(c, d + 1); s += X[c]; });
      X[id] = s / kids[id].length;
    }
    dfs(nodes[0].id, 0);
    return { X: X, Y: Y, W: Math.max(1, counter), H: maxD + 1 };
  }

  /* ============================================================
   * B013 三数之和 —— 排序 + 双指针
   * ============================================================ */
  D.push({
    id: 'B013', title: '三数之和', group: '数组 · 双指针 · 滑窗',
    render: 'array', star: 3,
    inputText: '-1,0,1,2,-1,-4 | 0',
    parse: function (t) {
      var p = t.split('|');
      return { nums: p[0].split(/[,\s]+/).filter(Boolean).map(Number), target: Number(p[1] || 0) };
    },
    desc: {
      intuition: '三层循环是 O(n³)。先排序，固定一个数 a[i]，剩下两个数就变成"在有序区间里找和为 target−a[i] 的一对" —— 左右指针一头一尾往中间夹，一次扫描 O(n)，总复杂度降到 O(n²)。',
      invariant: '固定 i 后，在 l < r 的每一步，区间 (l, r) 内所有与端点配对的组合都已被排除：和偏小时 l 左侧的组合只会更小，和偏大时 r 右侧的组合只会更大。因此 l/r 单调移动不会漏解。',
      steps: [
        '先对数组升序排序',
        '外层 i 从 0 扫到 n−3，遇到与前一个相同的数直接跳过（去重）',
        '内层 l = i+1、r = n−1，计算三数之和 s',
        's == target 记解并同时收缩 l、r；s < target 右移 l；s > target 左移 r',
        'l ≥ r 时换下一个 i'
      ],
      code: 'public List<List<Integer>> threeSum(int[] nums, int target) {\n    Arrays.sort(nums);\n    List<List<Integer>> ans = new ArrayList<>();\n    for (int i = 0; i < nums.length - 2; i++) {\n        if (i > 0 && nums[i] == nums[i - 1]) continue;   // 去重：同一个数不当两次基准\n        int l = i + 1, r = nums.length - 1;\n        while (l < r) {\n            int s = nums[i] + nums[l] + nums[r];\n            if (s == target) {\n                ans.add(Arrays.asList(nums[i], nums[l], nums[r]));\n                int lv = nums[l], rv = nums[r];\n                while (l < r && nums[l] == lv) l++;      // 跳过重复左端点\n                while (l < r && nums[r] == rv) r--;      // 跳过重复右端点\n            } else if (s < target) l++;\n            else r--;\n        }\n    }\n    return ans;\n}',
      complexity: '时间 O(n²) —— 排序 O(n log n)，外层 n 次 × 内层 l/r 合计移动 O(n)；空间 O(1) 额外（不计输出，排序原地）。',
      pitfall: '去重要做在两处：外层跳过重复的 i，内层找到解后要跳过重复的 l 和 r，否则 [0,0,0,0] 这种输入会输出重复解。另外判重必须在记录解之后立刻做，别等下一轮。'
    },
    run: function (inp) {
      var a = inp.nums.slice().sort(function (x, y) { return x - y; });
      var target = inp.target, n = a.length, f = [], res = [];
      function dsp(v) { return v < 0 ? '-' : v; }
      function fr(note, i, l, r, hl, cls) {
        var ptr = {};
        if (i >= 0) ptr.i = i;
        if (l >= 0) ptr.l = l;
        if (r >= 0) ptr.r = r;
        return {
          note: note,
          vars: { i: dsp(i), l: dsp(l), r: dsp(r), sum: (i >= 0 && l >= 0 && r >= 0) ? (a[i] + a[l] + a[r]) : '-', target: target },
          arr: a, hl: hl || [], ptr: ptr, cls: cls || {},
          res: res.map(function (t) { return '[' + t.join(', ') + ']'; })
        };
      }
      f.push(fr('先排序得到 [' + a.join(', ') + ']。有序是双指针能"有方向地移动"的前提。', -1, -1, -1, [], {}));
      for (var i = 0; i < n - 2; i++) {
        if (i > 0 && a[i] === a[i - 1]) {
          f.push(fr('a[' + i + '] = ' + a[i] + ' 与前一个数相同，跳过，避免产出重复解。', i, -1, -1, [i], {}));
          continue;
        }
        var l = i + 1, r = n - 1;
        f.push(fr('固定 i = ' + i + '（值 ' + a[i] + '），l = ' + l + '、r = ' + r + ' 从两端往中间夹。', i, l, r, [i, l, r], {}));
        while (l < r) {
          var s = a[i] + a[l] + a[r];
          f.push(fr('三数之和 = ' + a[i] + ' + ' + a[l] + ' + ' + a[r] + ' = ' + s + '，与目标 ' + target + ' 比较。', i, l, r, [i, l, r], {}));
          if (s === target) {
            res.push([a[i], a[l], a[r]]);
            var ck = {}; ck[i] = 'ok'; ck[l] = 'ok'; ck[r] = 'ok';
            f.push(fr('正好相等！记录一组解 [' + a[i] + ', ' + a[l] + ', ' + a[r] + ']，然后 l 右移、r 左移继续找。', i, l, r, [i, l, r], ck));
            var lv = a[l], rv = a[r];
            while (l < r && a[l] === lv) l++;
            while (l < r && a[r] === rv) r--;
          } else if (s < target) {
            f.push(fr(s + ' < ' + target + '，和偏小 —— 只有把 l 右移换一个更大的数才可能变大，r 左移只会更小。', i, l, r, [i, l, r], {}));
            l++;
          } else {
            f.push(fr(s + ' > ' + target + '，和偏大 —— 把 r 左移换一个更小的数。', i, l, r, [i, l, r], {}));
            r--;
          }
        }
      }
      f.push(fr('外层枚举结束，共找到 ' + res.length + ' 组不重复的解。', -1, -1, -1, [], {}));
      return f;
    }
  });

  /* ============================================================
   * A039 LRU 缓存 —— 哈希 + 双向链表
   * ============================================================ */
  D.push({
    id: 'A039', title: 'LRU 缓存', group: '设计 · 数据结构',
    render: 'list', star: 3,
    inputText: '2 | put 1 1, put 2 2, get 1, put 3 3, get 2, put 4 4, get 1, get 3, get 4',
    parse: function (t) {
      var p = t.split('|');
      var ops = p[1].split(',').map(function (s) { return s.trim(); }).filter(Boolean).map(function (s) {
        var w = s.split(/[\s(,)]+/).filter(Boolean);
        if (w[0] === 'put') return { op: 'put', k: w[1], v: Number(w[2]) };
        return { op: 'get', k: w[1] };
      });
      return { cap: Number(p[0]), ops: ops };
    },
    desc: {
      intuition: '要 O(1) 查，靠哈希表；要 O(1) 知道"谁最久没用"，靠链表维护访问顺序 —— 每次访问把节点挪到链表头，尾部自然就是最久未用的。两个结构拼起来：哈希表存 key → 节点引用，链表存顺序。',
      invariant: '链表从头部到尾部的顺序 = 访问新鲜度从新到旧。任意时刻 tail 前一个节点就是容量满时该淘汰的那个，不需要扫一遍去比时间戳。',
      steps: [
        'get(k)：表中没有返回 −1；有则取出节点值，并把该节点移到链表头',
        'put(k, v)：key 已存在则更新值并移到链表头',
        'key 不存在则新建节点插到链表头，同时在表中登记',
        '插入后若 size > capacity，删除链表尾节点，并从表中删掉它的 key'
      ],
      code: 'class LRUCache {\n    class Node { int k, v; Node pre, next; }\n    private final int cap;\n    private final Map<Integer, Node> map = new HashMap<>();\n    private final Node head = new Node(), tail = new Node();   // 哨兵，省掉空指针判断\n\n    LRUCache(int cap) { this.cap = cap; head.next = tail; tail.pre = head; }\n\n    public int get(int k) {\n        Node n = map.get(k);\n        if (n == null) return -1;\n        moveToHead(n);\n        return n.v;\n    }\n\n    public void put(int k, int v) {\n        Node n = map.get(k);\n        if (n != null) { n.v = v; moveToHead(n); return; }\n        n = new Node(); n.k = k; n.v = v;\n        map.put(k, n); addToHead(n);\n        if (map.size() > cap) { Node old = tail.pre; remove(old); map.remove(old.k); }\n    }\n\n    private void remove(Node n) { n.pre.next = n.next; n.next.pre = n.pre; }\n    private void addToHead(Node n) { n.pre = head; n.next = head.next; head.next.pre = n; head.next = n; }\n    private void moveToHead(Node n) { remove(n); addToHead(n); }\n}',
      complexity: '时间 O(1) —— get / put 都是哈希表定位 + 常数次指针改接；空间 O(capacity) —— 链表与哈希表各存 cap 项。',
      pitfall: '用哨兵节点（dummy head/tail）能消掉所有"是不是头尾"的边界判断，手写链表时这一招能省掉一半 bug。另一个坑：淘汰时别忘了同步删哈希表里的 key，否则同一个 key 再次 put 会复活一个已被回收的节点。'
    },
    run: function (inp) {
      var cap = inp.cap, order = [], f = [], ret = '-';
      function idxOf(k) { for (var i = 0; i < order.length; i++) if (order[i].k === k) return i; return -1; }
      function fr(note, hl, op, r, evict) {
        var stack = order.map(function (o, i) { return { t: o.k + ':' + o.v, s: (evict !== undefined && i === evict) ? 'evict' : '' }; });
        return {
          note: note, vars: { 容量: cap, 已用: order.length + '/' + cap, 操作: op, 返回: r },
          vals: order.map(function (o) { return o.k + ':' + o.v; }),
          next: order.map(function (_, i) { return i < order.length - 1 ? i + 1 : -1; }),
          ptr: order.length ? { head: 0, tail: order.length - 1 } : {},
          hl: hl || [], stack: stack, stackLabel: '链表（左=最近使用，右=最久未用）'
        };
      }
      f.push(fr('初始：容量 ' + cap + '，链表为空，head/tail 是哨兵节点。', [], 'init', '-'));
      inp.ops.forEach(function (o) {
        var label = o.op === 'put' ? 'put(' + o.k + ',' + o.v + ')' : 'get(' + o.k + ')';
        var i = idxOf(o.k);
        if (o.op === 'get') {
          if (i < 0) {
            ret = -1;
            f.push(fr('get(' + o.k + ')：表中没有这个 key，返回 −1，链表不变。', [], label, -1));
            return;
          }
          ret = order[i].v;
          f.push(fr('get(' + o.k + ')：命中！值为 ' + ret + '。命中即最新使用 —— 把该节点移到链表头。', [i], label, ret));
          var it = order.splice(i, 1)[0]; order.unshift(it);
          f.push(fr('节点已移到头部。' + o.k + ' 现在是最新的，最久未用的是 ' + (order.length ? order[order.length - 1].k : '-') + '。', [0], label, ret));
        } else {
          if (i >= 0) {
            order[i].v = o.v;
            var it2 = order.splice(i, 1)[0]; order.unshift(it2);
            ret = '更新';
            f.push(fr('put(' + o.k + ',' + o.v + ')：key 已存在，更新值为 ' + o.v + ' 并移到链表头，容量不变。', [0], label, o.v));
          } else {
            order.unshift({ k: o.k, v: o.v });
            ret = '新增';
            f.push(fr('put(' + o.k + ',' + o.v + ')：key 不存在，新建节点插到链表头，已用 ' + order.length + '/' + cap + '。', [0], label, o.v));
            if (order.length > cap) {
              var evicted = order.pop();
              f.push(fr('超出容量！淘汰链表尾部（最久未使用）的 key = ' + evicted.k + '，同时从哈希表删除它。', [order.length - 1], label, '淘汰' + evicted.k, order.length));
            }
          }
        }
      });
      f.push(fr('操作序列结束。链表头部是最近使用的，尾部是下一次会被淘汰的。', [], 'done', ret));
      return f;
    }
  });

  /* ============================================================
   * A015 前缀树 Trie
   * ============================================================ */
  D.push({
    id: 'A015', title: '前缀树 Trie', group: '设计 · 数据结构',
    render: 'tree', star: 3,
    inputText: 'apple, app, banana | app',
    parse: function (t) {
      var p = t.split('|');
      return {
        words: p[0].split(',').map(function (s) { return s.trim(); }).filter(Boolean),
        query: (p[1] || '').trim()
      };
    },
    desc: {
      intuition: '一堆字符串要反复查"有没有这个前缀"，用哈希表存全部字符串是 O(n·L)。Trie 把公共前缀合并成一条路径：查前缀时只走前缀长度那么多步，跟库里有多少词无关。',
      invariant: '从根到任意节点的路径上的字符拼起来 = 该节点代表的前缀；节点的 isEnd 标记了"这条路径本身是一个完整单词"。所以"查前缀"和"查单词"是同一套走法，只差最后看不看 isEnd。',
      steps: [
        '插入：从根出发按字符逐层走，字符不存在就新建子节点，走完把终点标 isEnd',
        '查单词：沿路径走，中途断掉返回 false，走完返回终点的 isEnd',
        '查前缀：沿路径走，走完即返回 true（不看 isEnd）',
        '子节点用 Map<Character, Node> 或定长数组（若字母表固定）'
      ],
      code: 'class Trie {\n    class Node {\n        Map<Character, Node> kids = new HashMap<>();\n        boolean end;\n    }\n    private final Node root = new Node();\n\n    public void insert(String w) {\n        Node cur = root;\n        for (char c : w.toCharArray())\n            cur = cur.kids.computeIfAbsent(c, k -> new Node());\n        cur.end = true;                      // 只有完整单词的终点才置位\n    }\n\n    public boolean search(String w) {\n        Node n = find(w);\n        return n != null && n.end;\n    }\n\n    public boolean startsWith(String p) {\n        return find(p) != null;\n    }\n\n    private Node find(String s) {\n        Node cur = root;\n        for (char c : s.toCharArray()) {\n            cur = cur.kids.get(c);\n            if (cur == null) return null;\n        }\n        return cur;\n    }\n}',
      complexity: '插入 / 查询 O(L) —— L 是词长，与词库规模无关；空间 O(总字符数)，最坏每个字符一个节点。',
      pitfall: 'search 与 startsWith 的差别只在最后那个 end 标记：搜 "app" 时若库里只有 "apple"，startsWith 是 true 而 search 必须是 false。漏掉 end 判断是这类题最常见的错。另外若场景允许，用定长数组 kids[26] 比 HashMap 快很多，但空间按 26 倍节点数算。'
    },
    run: function (inp) {
      var f = [], nodes = [{ id: 0, v: '根', end: false, kids: {} }], edges = [], nid = 1, parentOf = {};
      function kid(id, c) { return nodes[id].kids[c]; }
      function addKid(id, c) {
        var n = { id: nid, v: c, end: false, kids: {} };
        nid++; nodes.push(n); nodes[id].kids[c] = n.id; parentOf[n.id] = id; edges.push([id, n.id]);
        return n.id;
      }
      function snap(activeId) {
        var L = tidyLayout(nodes, parentOf);
        return {
          nodes: nodes.map(function (n) {
            var s = '';
            if (n.id === activeId) s = 'active';
            else if (n.end) s = 'sol';
            else if (Object.keys(n.kids).length === 0) s = 'done';
            return { id: n.id, v: n.v, x: L.X[n.id], y: L.Y[n.id], s: s };
          }),
          edges: edges.slice(), W: L.W, H: L.H
        };
      }
      function fr(note, act, vars) {
        var s = snap(act); s.note = note; s.vars = vars || {}; return s;
      }
      f.push(fr('初始只有根节点。开始逐个插入单词。', 0, { 阶段: '初始化' }));
      inp.words.forEach(function (w) {
        var cur = 0;
        f.push(fr('插入 "' + w + '"：从根开始逐字符下降。', 0, { 插入: w, 字符: '-' }));
        for (var i = 0; i < w.length; i++) {
          var c = w.charAt(i), nx = kid(cur, c);
          if (nx === undefined) {
            nx = addKid(cur, c);
            f.push(fr('字符 ' + c + ' 不存在，新建节点（第 ' + (i + 1) + '/' + w.length + ' 个字符）。', nx, { 插入: w, 字符: c, 动作: '新建' }));
          } else {
            f.push(fr('字符 ' + c + ' 已存在，直接复用已有节点 —— 这正是 Trie 节省空间的地方。', nx, { 插入: w, 字符: c, 动作: '复用' }));
          }
          cur = nx;
        }
        nodes[cur].end = true;
        f.push(fr('"' + w + '" 走完，把终点节点标记为完整单词（isEnd = true）。', cur, { 插入: w, isEnd: 'true' }));
      });
      var q = inp.query;
      if (q) {
        var cur2 = 0, ok = true;
        f.push(fr('现在查询 "' + q + '"：从根逐字符下降，中途断掉即失败。', 0, { 查询: q }));
        for (var j = 0; j < q.length; j++) {
          var c2 = q.charAt(j), n2 = kid(cur2, c2);
          if (n2 === undefined) {
            var s2 = snap(cur2); s2.note = '字符 ' + c2 + ' 在 Trie 中不存在，查询失败，返回 false。';
            s2.vars = { 查询: q, 结果: 'false' };
            f.push(s2); ok = false; break;
          }
          cur2 = n2;
          f.push(fr('匹配到字符 ' + c2 + '，继续下降（' + (j + 1) + '/' + q.length + '）。', cur2, { 查询: q, 结果: '进行中' }));
        }
        if (ok) {
          var hit = nodes[cur2].end;
          f.push(fr('路径走完了。这个节点的 isEnd = ' + hit + '，所以 search("' + q + '") = ' + hit + '（startsWith 则为 true）。',
            cur2, { 查询: q, isEnd: String(hit), 结果: String(hit) }));
        }
      }
      return f;
    }
  });

  /* ============================================================
   * A010 全排列 —— 回溯决策树
   * ============================================================ */
  D.push({
    id: 'A010', title: '全排列（回溯决策树）', group: '回溯 · 组合搜索',
    render: 'tree', star: 3,
    inputText: '1,2,3',
    parse: function (t) { return { nums: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '别把回溯想成"递归"，把它想成在一棵决策树上做 DFS：第 k 层选第 k 个位置的数，每一层的分支就是"还有哪些数没被用过"。递归只是写 DFS 的语法糖。',
      invariant: '递归进入第 depth 层时，path 里已经有 depth 个已选定的数，used 标记了它们；本层可选的分支恰好是 used 为 false 的那些数。恢复现场（撤销 used 与 path 的最后一个）后，回到父节点时状态与进入前完全一致 —— 这就是"回溯"两个字的意思。',
      steps: [
        '维护 path（当前已选）与 used（哪些数已被选走）',
        '递归：若 path 长度 == n，记下这一份解并返回',
        '否则遍历所有未使用的数，做"选 → 递归 → 撤销"三步',
        '撤销是必须的：不撤销的话兄弟分支会看到脏状态'
      ],
      code: 'public List<List<Integer>> permute(int[] nums) {\n    List<List<Integer>> ans = new ArrayList<>();\n    boolean[] used = new boolean[nums.length];\n    dfs(nums, used, new ArrayList<>(), ans);\n    return ans;\n}\n\nprivate void dfs(int[] nums, boolean[] used, List<Integer> path, List<List<Integer>> ans) {\n    if (path.size() == nums.length) { ans.add(new ArrayList<>(path)); return; }  // 必须拷一份\n    for (int i = 0; i < nums.length; i++) {\n        if (used[i]) continue;\n        used[i] = true;  path.add(nums[i]);      // 做选择\n        dfs(nums, used, path, ans);\n        used[i] = false; path.remove(path.size() - 1);   // 撤销选择\n    }\n}',
      complexity: '时间 O(n × n!) —— 共 n! 个叶子，每个叶子拷贝路径 O(n)；空间 O(n) 递归栈（不计输出）。',
      pitfall: 'ans.add(path) 是绝大多数人踩的坑：存进去的是同一个 List 的引用，回溯一路把它改空了，最后得到 n! 份全空的答案。必须 new ArrayList<>(path) 拷一份。'
    },
    run: function (inp) {
      var nums = inp.nums, n = nums.length, f = [];
      var nodes = [{ id: 0, v: '根', parent: null, digit: null, depth: 0 }];
      var parentOf = { 0: null }, subMax = {}, used2 = new Array(n).fill(false);
      (function build(id, depth) {
        var mx = id;
        for (var i = 0; i < n; i++) {
          if (used2[i]) continue;
          used2[i] = true;
          var cid = nodes.length;
          nodes.push({ id: cid, v: String(nums[i]), digit: nums[i], parent: id, depth: depth + 1, sol: (depth + 1 === n) });
          parentOf[cid] = id;
          var r = build(cid, depth + 1);
          used2[i] = false;
          if (r > mx) mx = r;
        }
        subMax[id] = mx;
        return mx;
      })(0, 0);

      var L = tidyLayout(nodes, parentOf);
      var solCount = 0;
      function stateAt(k) {
        var onPath = {};
        var x = k;
        while (x !== null && x !== undefined) { onPath[x] = 1; x = nodes[x].parent; }
        return nodes.filter(function (nd) { return nd.id <= k; }).map(function (nd) {
          var s = '';
          if (nd.sol) s = 'sol';
          else if (nd.id === k) s = 'active';
          else if (onPath[nd.id]) s = 'path';
          else if (subMax[nd.id] <= k) s = 'done';
          return { id: nd.id, v: nd.v, x: L.X[nd.id], y: L.Y[nd.id], s: s };
        });
      }
      function pathStr(k) {
        var st = [], x = k;
        while (x !== null && x !== undefined) { if (nodes[x].digit !== null) st.unshift(nodes[x].digit); x = nodes[x].parent; }
        return st.join(' → ') || '空';
      }
      for (var k = 0; k < nodes.length; k++) {
        var nd = nodes[k];
        var note;
        if (k === 0) note = '决策树根节点：什么都还没选，第 1 个位置有 ' + n + ' 种选择。';
        else if (nd.sol) { solCount++; note = 'path 长度达到 ' + n + '，得到一个完整排列 ' + pathStr(k) + '，记入答案（第 ' + solCount + ' 个）。'; }
        else note = '选择 ' + nd.v + ' 放到第 ' + nd.depth + ' 位，当前路径：' + pathStr(k) + '。向下继续展开。';
        f.push({
          note: note,
          vars: { 层数: nd.depth, 当前路径: pathStr(k), 已得解: solCount },
          nodes: stateAt(k), edges: (function () {
            var e = [];
            nodes.forEach(function (m) { if (m.parent !== null && m.id <= k) e.push([m.parent, m.id]); });
            return e;
          })(), W: L.W, H: L.H
        });
      }
      f.push({
        note: '全部 ' + solCount + ' 个排列枚举完毕。注意：每次从叶子返回父节点时都会撤销那一步选择，父节点才能换下一个分支 —— 这就是回溯。',
        vars: { 层数: '-', 当前路径: '空', 已得解: solCount },
        nodes: stateAt(nodes.length - 1), edges: (function () {
          var e = []; nodes.forEach(function (m) { if (m.parent !== null) e.push([m.parent, m.id]); }); return e;
        })(), W: L.W, H: L.H
      });
      return f;
    }
  });

  /* ============================================================
   * A032 买卖股票（含冷冻期）—— 状态机 DP
   * ============================================================ */
  D.push({
    id: 'A032', title: '买卖股票最佳时机（含冷冻期）', group: 'DP 表格填充',
    render: 'dp', star: 3,
    inputText: '1,2,3,0,2',
    parse: function (t) { return { prices: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '"冷冻期"让人纠结，是因为一天的决定会影响下一天能不能买。破解办法是把"能不能操作"显式建模成状态：每天只有三种处境 —— 持股、今天刚卖（明天冷冻）、空仓（且不是今天卖的）。把状态画出来，DP 转移就是状态图上的边。',
      invariant: 'dp[i][s] = 第 i 天结束时处在状态 s 的最大现金。它只依赖 dp[i−1] 的三个值，所以三种状态可以互相推导，且推导顺序不含环 —— 一天一天往前推即可。',
      steps: [
        '定义三状态：0 持股 / 1 今天卖出（明天冷冻）/ 2 空仓且非冷冻',
        '持股：要么昨天就持股，要么今天买入 —— 只有空仓（状态 2）能买，刚卖的今天不能买',
        '今天卖：昨天必须持股，卖完得到 price[i]',
        '空仓：昨天空仓，或昨天刚卖（冷冻一天结束）',
        '答案是最后一天的 max(刚卖, 空仓)，持股状态不可能最优'
      ],
      code: 'public int maxProfit(int[] p) {\n    int n = p.length;\n    if (n == 0) return 0;\n    int[][] dp = new int[n][3];\n    dp[0][0] = -p[0];                       // 第 0 天买入\n    for (int i = 1; i < n; i++) {\n        dp[i][0] = Math.max(dp[i-1][0], dp[i-1][2] - p[i]);   // 继续持有 / 今天买入\n        dp[i][1] = dp[i-1][0] + p[i];                          // 今天卖出\n        dp[i][2] = Math.max(dp[i-1][2], dp[i-1][1]);           // 继续空仓 / 冷冻结束\n    }\n    return Math.max(dp[n-1][1], dp[n-1][2]);\n}',
      complexity: '时间 O(n) —— 每天三个常数转移；空间 O(n)，滚动成三个变量后 O(1)。',
      pitfall: '买入的转移只能来自状态 2（空仓且非冷冻），不能来自状态 1（今天刚卖）—— 那正是冷冻期要禁止的操作。写反了就变成"没有冷冻期"的题，样例一样能过但题意错了。另外 dp[0][1] 不能初始化成 0 以外的负无穷语义，第 0 天刚卖等于不操作，取 0 是对的。'
    },
    run: function (inp) {
      var p = inp.prices, n = p.length, f = [], rows = [], rl = [], cl = ['持股', '刚卖(冷冻)', '空仓'];
      var dp = [];
      for (var i = 0; i < n; i++) dp.push([0, 0, 0]);
      dp[0][0] = -p[0];
      rows.push([dp[0][0], dp[0][1], dp[0][2]]);
      rl.push('第1天 (' + p[0] + ')');
      f.push({
        note: '第 1 天：价格 ' + p[0] + '。持股 = −' + p[0] + '（花钱买入），刚卖与空仓都只能是 0（还没卖过）。',
        vars: { 天: 1, 价格: p[0] }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [0, 0], src: []
      });
      for (var i2 = 1; i2 < n; i2++) {
        rows.push([0, 0, 0]); rl.push('第' + (i2 + 1) + '天 (' + p[i2] + ')');
        var v0 = Math.max(dp[i2 - 1][0], dp[i2 - 1][2] - p[i2]);
        rows[i2][0] = v0;
        f.push({
          note: '第 ' + (i2 + 1) + ' 天持股：max(昨天持股 ' + dp[i2 - 1][0] + '，昨天空仓 ' + dp[i2 - 1][2] + ' − 今天价格 ' + p[i2] + ') = ' + v0 + '。注意只能从"空仓"买，不能从"刚卖"买 —— 这就是冷冻期。',
          vars: { 天: i2 + 1, 价格: p[i2] }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [i2, 0],
          src: [[i2 - 1, 0], [i2 - 1, 2]]
        });
        var v1 = dp[i2 - 1][0] + p[i2];
        rows[i2][1] = v1;
        f.push({
          note: '第 ' + (i2 + 1) + ' 天刚卖出：昨天持股 ' + dp[i2 - 1][0] + ' + 今天价格 ' + p[i2] + ' = ' + v1 + '。卖完明天就进入冷冻，不能买。',
          vars: { 天: i2 + 1, 价格: p[i2] }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [i2, 1],
          src: [[i2 - 1, 0]]
        });
        var v2 = Math.max(dp[i2 - 1][2], dp[i2 - 1][1]);
        rows[i2][2] = v2;
        f.push({
          note: '第 ' + (i2 + 1) + ' 天空仓：max(昨天空仓 ' + dp[i2 - 1][2] + '，昨天刚卖 ' + dp[i2 - 1][1] + ') = ' + v2 + '。昨天卖的今天冷冻结束，转为空仓。',
          vars: { 天: i2 + 1, 价格: p[i2] }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [i2, 2],
          src: [[i2 - 1, 1], [i2 - 1, 2]]
        });
        dp[i2] = [v0, v1, v2];
      }
      var ans = Math.max(dp[n - 1][1], dp[n - 1][2]);
      f.push({
        note: '最后一天：持股 ' + dp[n - 1][0] + ' 一定不划算（手里还有没卖的股票），答案取 max(刚卖 ' + dp[n - 1][1] + '，空仓 ' + dp[n - 1][2] + ') = ' + ans + '。',
        vars: { 天: n, 答案: ans }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [n - 1, 1],
        src: [[n - 1, 1], [n - 1, 2]]
      });
      return f;
    }
  });

  /* ============================================================
   * A018 等式方程可满足性 —— 并查集
   * ============================================================ */
  D.push({
    id: 'A018', title: '等式方程可满足性（并查集）', group: '图 · 拓扑 · 最短路',
    render: 'graph', star: 3,
    inputText: 'a==b, b!=c, c==a',
    parse: function (t) {
      return {
        eqs: t.split(',').map(function (s) { return s.trim(); }).filter(Boolean).map(function (s) {
          var m = s.match(/^([A-Za-z])(==|!=)([A-Za-z])$/);
          if (!m) throw new Error('无法解析：' + s + '（应形如 a==b 或 a!=b）');
          return { a: m[1], b: m[3], op: m[2] };
        })
      };
    },
    desc: {
      intuition: '"==" 是等价关系，具有传递性 —— 先把所有等号合并成一个个集合，再去检查每个不等号的两端是不是被不小心圈进了同一个集合。维护"集合归属 + 合并"这件事，并查集是 O(α(n)) 的。',
      invariant: '任意时刻，find(x) 返回 x 所在集合的代表元；同一个集合里任意两点的 find 结果相同。因此 a != b 可满足的充要条件是 find(a) != find(b)。',
      steps: [
        '初始化：每个变量各自成一个集合，parent[x] = x',
        '第一遍只处理 ==，把两端 union 起来',
        '第二遍处理 !=，若 find(a) == find(b) 则矛盾，返回 false',
        '全部通过返回 true',
        '优化：路径压缩 + 按秩/大小合并，让 find 接近 O(1)'
      ],
      code: 'public boolean equationsPossible(String[] eq) {\n    int[] p = new int[26];\n    for (int i = 0; i < 26; i++) p[i] = i;\n    for (String s : eq)                      // 先合并所有等号\n        if (s.charAt(1) == 61)               // \'=\'\n            union(p, s.charAt(0) - 97, s.charAt(3) - 97);\n    for (String s : eq)                      // 再检查不等号\n        if (s.charAt(1) == 33)               // \'!\'\n            if (find(p, s.charAt(0) - 97) == find(p, s.charAt(3) - 97))\n                return false;\n    return true;\n}\n\nint find(int[] p, int x) { return p[x] == x ? x : (p[x] = find(p, p[x])); }   // 路径压缩\nvoid union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }',
      complexity: '时间 O(n · α(n)) ≈ O(n) —— α 是反阿克曼函数，实际不超过 4；空间 O(1)（26 个字母）或 O(变量数)。',
      pitfall: '必须两遍扫描：先合并全部等号，再检查不等号。一遍扫描的话，先遇到的不等号在后续等号合并后可能变成矛盾，会漏判。另外路径压缩的写法是 p[x] = find(p, p[x]) 而不是 find 一遍就完事，漏掉赋值会让树退化成链。'
    },
    run: function (inp) {
      var f = [], vars = [], posMap = {}, POS = [[20, 22], [78, 22], [50, 82], [20, 82], [78, 82], [50, 22]];
      inp.eqs.forEach(function (e) {
        [e.a, e.b].forEach(function (c) {
          if (vars.indexOf(c) < 0) { vars.push(c); posMap[c] = POS[vars.length - 1] || [50, 50]; }
        });
      });
      var parent = {};
      vars.forEach(function (c) { parent[c] = c; });
      function find(x) { while (parent[x] !== x) x = parent[x]; return x; }
      function snap(note, act, cur, ee, tag) {
        return {
          note: note, vars: { 当前: cur || '-', 代表元: vars.map(function (c) { return c + '→' + find(c); }).join('  ') },
          nodes: vars.map(function (c) {
            var s = '';
            if (tag && tag.bad && (c === tag.bad[0] || c === tag.bad[1])) s = 'cycle';
            else if (act === c) s = 'relax';
            else if (find(c) !== c) s = 'done';
            else s = 'ready';
            return { id: c, x: posMap[c][0], y: posMap[c][1], s: s, tag: '父:' + parent[c] };
          }),
          edges: ee || []
        };
      }
      var edges = [];
      f.push(snap('初始化：每个变量自成一组，parent[x] = x。先处理所有等号，把它们合并。', null, '-', []));
      inp.eqs.forEach(function (e) {
        if (e.op !== '==') return;
        var ra = find(e.a), rb = find(e.b);
        if (ra === rb) {
          f.push(snap(e.a + ' 和 ' + e.b + ' 已经在同一个集合（代表元都是 ' + ra + '），无需合并。', e.a, e.a + e.op + e.b, edges.slice()));
          return;
        }
        parent[ra] = rb;
        edges.push({ a: e.a, b: e.b, s: 'union' });
        f.push(snap('处理 ' + e.a + ' == ' + e.b + '：把 ' + ra + ' 所在的集合挂到 ' + rb + ' 名下，两个集合合并。',
          e.a, e.a + e.op + e.b, edges.slice()));
      });
      var res = true;
      inp.eqs.forEach(function (e) {
        if (e.op !== '!=') return;
        var ra = find(e.a), rb = find(e.b);
        if (ra === rb) {
          res = false;
          f.push(snap('检查 ' + e.a + ' != ' + e.b + '：find(' + e.a + ') = ' + ra + '，find(' + e.b + ') = ' + rb + '，两者相同 —— 既要求相等又要求不等，矛盾！', null, e.a + e.op + e.b, edges.slice(), { bad: [e.a, e.b] }));
        } else {
          f.push(snap('检查 ' + e.a + ' != ' + e.b + '：代表元分别是 ' + ra + ' 和 ' + rb + '，不在同一集合，这个不等式可以满足。', null, e.a + e.op + e.b, edges.slice()));
        }
      });
      f.push(snap(res ? '所有不等式都通过，这组方程可满足，返回 true。' : '存在矛盾，返回 false。', null, res ? 'true' : 'false', edges.slice()));
      return f;
    }
  });

  /* ============================================================
   * A007 柱状图最大矩形 —— 单调栈
   * ============================================================ */
  D.push({
    id: 'A007', title: '柱状图最大矩形（单调栈）', group: '栈 · 队列 · 单调栈',
    render: 'bars', star: 3,
    inputText: '2,1,5,6,2,3',
    parse: function (t) { return { h: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '暴力做法是枚举每个柱子向左向右找第一个更矮的，O(n²)。单调栈把这件事压成 O(n)：栈里存下标并保证对应高度递增，一旦遇到一个比栈顶更矮的柱子，栈顶那根的"右边界"就确定了 —— 它再也长不过去了，可以立刻结算。',
      invariant: '栈中下标对应的高度严格递增。当第 i 根柱子比栈顶矮时，弹出栈顶 j：j 的右边界是 i−1，左边界是弹出后新的栈顶（栈空则为 0），宽度由此唯一确定。每根柱子只进栈一次、出栈一次，所以总操作是线性的。',
      steps: [
        '栈存下标，保持对应高度单调递增',
        '遍历每根柱子（末尾补一个高度 0 的哨兵，用来强制清空栈）',
        '若当前高度 < 栈顶高度：反复弹栈，每弹出一根就以其高度为矩形高、以左右边界算面积并更新答案',
        '否则把当前下标压栈',
        '哨兵高度 0 保证最后所有柱子都会被弹出结算'
      ],
      code: 'public int largestRectangleArea(int[] h) {\n    Deque<Integer> st = new ArrayDeque<>();\n    int ans = 0;\n    for (int i = 0; i <= h.length; i++) {\n        int cur = (i == h.length) ? 0 : h[i];      // 哨兵：高度 0\n        while (!st.isEmpty() && h[st.peek()] > cur) {\n            int hh = h[st.pop()];\n            int left = st.isEmpty() ? 0 : st.peek() + 1;\n            int width = i - left;\n            ans = Math.max(ans, hh * width);\n        }\n        st.push(i);\n    }\n    return ans;\n}',
      complexity: '时间 O(n) —— 每个下标恰好入栈一次、出栈一次；空间 O(n) —— 栈最多存 n 个下标。',
      pitfall: '末尾的哨兵（高度 0）不是可选项，没有它，遍历结束时栈里剩下的递增序列永远不会被结算。另外循环条件必须是 h[栈顶] > cur（严格大于），写成 >= 在存在相等高度时也能得出正确答案但会多做几次无意义结算，容易把自己绕晕。宽度是 i − left 而不是 i − left + 1，因为 left 已经是"第一个更矮的下标 + 1"。'
    },
    run: function (inp) {
      var h = inp.h, n = h.length, f = [], ans = 0, st = [];
      function fr(note, i, hlIdx, cls, water) {
        var bars = h.map(function (v, k) {
          var s = cls && cls[k] ? cls[k] : '';
          if (k === i) s = 'active';
          return { v: v, s: s };
        });
        return {
          note: note,
          vars: { i: i < n ? i : '哨兵', cur: i < n ? h[i] : 0, 栈顶: st.length ? (st[st.length - 1] + '(' + h[st[st.length - 1]] + ')') : '空', 最大面积: ans },
          bars: bars, ptr: i < n ? { i: i } : {}, water: water || null,
          stack: st.map(function (x) { return { t: '#' + x + ':' + h[x] }; })
        };
      }
      f.push(fr('初始：栈为空，最大面积 0。栈内下标对应的高度始终递增。', 0, [], {}));
      for (var i = 0; i <= n; i++) {
        var cur = (i === n) ? 0 : h[i];
        if (i === n) f.push(fr('遍历结束，压入高度为 0 的哨兵，用它把栈里剩下的柱子全部逼出来结算。', i, [], {}));
        else f.push(fr('第 ' + i + ' 根柱子，高度 ' + cur + '。与栈顶高度比较：' + (st.length ? h[st[st.length - 1]] : '栈空') + '。', i, [], {}));
        while (st.length && h[st[st.length - 1]] > cur) {
          var j = st.pop();
          var hh = h[j];
          var left = st.length ? st[st.length - 1] + 1 : 0;
          var w = i - left;
          var area = hh * w;
          var cls = {}; cls[j] = 'done';
          for (var q = left; q < i; q++) if (cls[q] === undefined) cls[q] = 'win';
          var isNew = area > ans;
          if (isNew) ans = area;
          f.push(fr('当前高度 ' + cur + ' < 栈顶高度 ' + hh + '，#' + j + ' 的右边界确定为 ' + (i - 1) + '。左边界 = ' + left + '，宽度 = ' + i + ' − ' + left + ' = ' + w + '，面积 = ' + hh + ' × ' + w + ' = ' + area + (isNew ? '，刷新最大值！' : '，未超过当前最大 ' + ans + '。'),
            i, [], cls, [left, i, Math.min(hh, Math.max.apply(null, h))]));
        }
        if (i < n) {
          st.push(i);
          f.push(fr('把下标 ' + i + '（高度 ' + h[i] + '）压入栈，栈内高度保持递增。', i, [], {}));
        }
      }
      f.push(fr('栈已清空，所有柱子都结算完毕。最大矩形面积 = ' + ans + '。', n, [], {}));
      return f;
    }
  });

  /* ============================================================
   * B036 二叉搜索树最近公共祖先
   * ============================================================ */
  D.push({
    id: 'B036', title: 'BST 最近公共祖先', group: '树 · 遍历递归',
    render: 'tree', star: 3,
    inputText: '6,2,8,0,4,7,9,null,null,3,5 | 0,5',
    parse: function (t) {
      var p = t.split('|');
      var arr = p[0].split(',').map(function (s) { s = s.trim(); return (s === 'null' || s === '') ? null : Number(s); });
      var pq = (p[1] || '').split(',').map(Number);
      return { arr: arr, p: pq[0], q: pq[1] };
    },
    desc: {
      intuition: '普通二叉树找 LCA 要遍历整棵树，BST 不用 —— 它的中序有序性直接告诉你该往哪边走：如果两个目标都比当前节点小，LCA 一定在左子树；都比它大，在右子树；一旦一个在左一个在右（或有一个就是当前节点），当前节点就是分岔点，也就是 LCA。',
      invariant: '从根往下走的每一步，当前节点都仍是 p 和 q 的公共祖先。走到第一个"p、q 不再落在同一侧"的节点时，它必然是最深的那个公共祖先 —— 再往下走就有一方不在子树里了。',
      steps: [
        'cur = root',
        '若 p、q 都小于 cur.val，cur = cur.left（LCA 在左子树）',
        '若 p、q 都大于 cur.val，cur = cur.right',
        '否则 cur 就是分岔点，返回它（包含"其中一个就是 cur 自身"的情况）',
        '迭代写即可，不需要递归，空间 O(1)'
      ],
      code: 'public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {\n    TreeNode cur = root;\n    while (cur != null) {\n        if (p.val < cur.val && q.val < cur.val) cur = cur.left;\n        else if (p.val > cur.val && q.val > cur.val) cur = cur.right;\n        else return cur;              // 分岔点，或命中其中一个\n    }\n    return null;\n}',
      complexity: '时间 O(h) —— h 是树高，平衡 BST 为 O(log n)，退化成链时 O(n)；空间 O(1)（迭代写法）。',
      pitfall: '判断要用"同侧"而不是"是否相等"：条件是 p 和 q 同时小于（或同时大于）cur，只有当它们分居两侧、或其中一个等于 cur 时才停下。写成"找到 p 或 q 就返回"是普通二叉树的思路，在 BST 里会错过真正的分岔点。'
    },
    run: function (inp) {
      var arr = inp.arr, f = [], nodes = [], edges = [];
      var root = { id: 0, v: arr[0], left: null, right: null };
      nodes.push(root);
      var q = [root], idx = 1;
      while (q.length && idx < arr.length) {
        var curN = q.shift();
        if (arr[idx] !== null) { var l = { id: nodes.length, v: arr[idx], left: null, right: null }; nodes.push(l); curN.left = l; q.push(l); edges.push([curN.id, l.id]); }
        idx++;
        if (idx < arr.length && arr[idx] !== null) { var r = { id: nodes.length, v: arr[idx], left: null, right: null }; nodes.push(r); curN.right = r; q.push(r); edges.push([curN.id, r.id]); }
        idx++;
      }
      // 布局：x 取中序序号，y 取深度
      var X = {}, Y = {}, c = 0, maxD = 0;
      (function inorder(n, d) {
        if (!n) return;
        inorder(n.left, d + 1);
        X[n.id] = c++; Y[n.id] = d; if (d > maxD) maxD = d;
        inorder(n.right, d + 1);
      })(root, 0);
      var W = c, H = maxD + 1;
      function snap(note, actId, doneIds, vars) {
        return {
          note: note, vars: vars || {}, W: W, H: H, edges: edges,
          nodes: nodes.map(function (n) {
            var s = '';
            if (n.id === actId) s = 'active';
            else if (doneIds.indexOf(n.id) >= 0) s = 'done';
            else if (n.v === inp.p || n.v === inp.q) s = 'path';
            return { id: n.id, v: n.v, x: X[n.id], y: Y[n.id], s: s };
          })
        };
      }
      f.push(snap('BST 已建好（中序有序）。要找 ' + inp.p + ' 和 ' + inp.q + ' 的最近公共祖先，从根 ' + root.v + ' 出发。', 0, [], { p: inp.p, q: inp.q, cur: root.v }));
      var cur = root, done = [], target = null;
      while (cur) {
        if (inp.p < cur.v && inp.q < cur.v) {
          f.push(snap(inp.p + ' 和 ' + inp.q + ' 都小于 ' + cur.v + ' —— 两个目标都在左子树，LCA 一定也在左边，往左走。', cur.id, done, { p: inp.p, q: inp.q, cur: cur.v, 判断: '同在左' }));
          done.push(cur.id); cur = cur.left;
        } else if (inp.p > cur.v && inp.q > cur.v) {
          f.push(snap(inp.p + ' 和 ' + inp.q + ' 都大于 ' + cur.v + ' —— 都在右子树，往右走。', cur.id, done, { p: inp.p, q: inp.q, cur: cur.v, 判断: '同在右' }));
          done.push(cur.id); cur = cur.right;
        } else {
          target = cur;
          f.push(snap('分岔了！' + inp.p + ' 和 ' + inp.q + ' 不再落在 ' + cur.v + ' 的同一侧（或其中一个正是它）。' + cur.v + ' 就是最深的公共祖先，返回它。', cur.id, done, { p: inp.p, q: inp.q, cur: cur.v, 答案: cur.v }));
          break;
        }
      }
      if (target) {
        f.push(snap('答案：LCA = ' + target.v + '。整条路径只走了 ' + (done.length + 1) + ' 个节点，完全不需要遍历全树。', target.id, done, { p: inp.p, q: inp.q, 答案: target.v }));
      }
      return f;
    }
  });

  /* ============================================================
   * A033 分割等和子集 —— 01 背包
   * ============================================================ */
  D.push({
    id: 'A033', title: '分割等和子集（01 背包）', group: 'DP 表格填充',
    render: 'dp', star: 3,
    inputText: '1,5,11,5',
    parse: function (t) { return { nums: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '"能不能分成两个和相等的子集"翻译成"能不能挑出一些数，凑出总和的一半" —— 这就是标准的 01 背包：每个数要么选要么不选，问容量恰好为 target 能不能装满。',
      invariant: 'dp[i][j] = 只考虑前 i 个数时，能否凑出和 j。转移只有两条路：不选第 i 个数（继承 dp[i−1][j]），或选它（看 dp[i−1][j−num]）。因为每个数只能用一次，j 必须从大到小枚举，否则同一个数会被反复使用（那就变成完全背包了）。',
      steps: [
        '求和 sum，若为奇数直接 false；target = sum / 2',
        '若最大数 > target 也直接 false',
        'dp[0][0] = true，其余初始为 false',
        '对每个数，从 j = target 倒序到 num：dp[j] |= dp[j − num]',
        '返回 dp[target]'
      ],
      code: 'public boolean canPartition(int[] nums) {\n    int sum = 0;\n    for (int x : nums) sum += x;\n    if ((sum & 1) == 1) return false;\n    int target = sum / 2;\n    boolean[] dp = new boolean[target + 1];\n    dp[0] = true;                       // 什么都不选，和为 0\n    for (int x : nums)\n        for (int j = target; j >= x; j--)   // 必须倒序，保证每个数只用一次\n            dp[j] |= dp[j - x];\n    return dp[target];\n}',
      complexity: '时间 O(n · target) —— 每个数 × 每个容量；空间 O(target) —— 一维数组滚动。target = sum/2，所以这是伪多项式复杂度。',
      pitfall: '内层 j 倒序是本题的灵魂。正序的话 dp[j−x] 已经在本次循环里被更新过，等于允许第 i 个数被选多次，题目就变成了完全背包，答案会偏大（本例 [1,5,11,5] 会误判成 true）。另外记得先判 sum 奇偶，能省掉一半无用计算。'
    },
    run: function (inp) {
      var nums = inp.nums, f = [], sum = nums.reduce(function (a, b) { return a + b; }, 0);
      if (sum % 2 === 1) {
        f.push({ note: '总和 ' + sum + ' 是奇数，不可能平分，直接返回 false。', vars: { sum: sum, 答案: 'false' }, rows: [['-']], rl: ['—'], cl: ['sum 为奇数'], cur: null, src: [] });
        return f;
      }
      var target = sum / 2, n = nums.length;
      var dp = new Array(target + 1).fill(false);
      dp[0] = true;
      var rows = [dp.map(function (b) { return b ? '✓' : '·'; })];
      var rl = ['无物品'];
      var cl = [];
      for (var j0 = 0; j0 <= target; j0++) cl.push(String(j0));
      f.push({
        note: '总和 ' + sum + '，目标 target = ' + sum + ' / 2 = ' + target + '。dp[j] 表示能否凑出和 j，初始只有 dp[0] = true（什么都不选）。',
        vars: { sum: sum, target: target }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [0, 0], src: []
      });
      for (var i = 0; i < n; i++) {
        var x = nums[i];
        rows.push(rows[rows.length - 1].slice());
        rl.push('物品' + (i + 1) + '(' + x + ')');
        f.push({
          note: '考虑第 ' + (i + 1) + ' 个数 ' + x + '。内层 j 从 target = ' + target + ' 倒序降到 ' + x + ' —— 倒序是为了让每个数只被用一次。',
          vars: { 物品: x, target: target }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [i + 1, target], src: [[i, target]]
        });
        for (var j = target; j >= x; j--) {
          var before = dp[j];
          if (dp[j - x]) dp[j] = true;
          rows[i + 1][j] = dp[j] ? '✓' : '·';
          var src = [[i, j]];
          if (j - x >= 0) src.push([i, j - x]);
          f.push({
            note: 'j = ' + j + '：dp[' + j + '] 原为 ' + (before ? 'true' : 'false') + '，dp[' + (j - x) + '] = ' + (dp[j - x] ? 'true' : 'false') +
              (dp[j - x] ? ' —— 选上 ' + x + ' 可以凑出 ' + j + '，置为 true。' : ' —— 装不下或凑不出，保持不变。'),
            vars: { 物品: x, j: j, 'dp[j-x]': dp[j - x] ? 'true' : 'false' }, rows: clone(rows), rl: rl.slice(), cl: cl,
            cur: [i + 1, j], src: src
          });
        }
      }
      var ans = dp[target];
      f.push({
        note: '全部物品考虑完毕。dp[' + target + '] = ' + ans + '，因此' + (ans ? '存在一种选法凑出 ' + target + '，剩下的数之和自然也是 ' + target + '，可以平分。' : '凑不出 ' + target + '，无法平分。'),
        vars: { target: target, 答案: String(ans) }, rows: clone(rows), rl: rl.slice(), cl: cl, cur: [n, target],
        src: [[n, target]]
      });
      return f;
    }
  });

  /* ============================================================
   * B023 环形链表入口 —— Floyd 快慢指针
   * ============================================================ */
  D.push({
    id: 'B023', title: '环形链表入口（Floyd）', group: '链表指针重连',
    render: 'list', star: 3,
    inputText: '3,2,0,-4 | 1',
    parse: function (t) {
      var p = t.split('|');
      return { vals: p[0].split(/[,\s]+/).filter(Boolean).map(Number), pos: Number(p[1] || 0) };
    },
    desc: {
      intuition: '判断有没有环用快慢指针就够了，但"入口在哪"才是难点。Floyd 的巧妙之处在于：快慢指针相遇后，把一个指针放回头部，两个指针改成同速前进，它们再次相遇的位置恰好就是入口 —— 不需要知道环长，也不需要额外空间。',
      invariant: '设头到入口距离 a，入口到相遇点距离 b，环长 c。相遇时慢指针走了 a+b，快指针走了 2(a+b)，而快指针比慢指针多走的部分是环长的整数倍，即 2(a+b) − (a+b) = a+b = k·c，于是 a = k·c − b。这意味着：从相遇点再走 a 步会回到入口，而从头部走 a 步也到入口 —— 两指针同速前进必然在入口相遇。',
      steps: [
        'slow 每次 1 步，fast 每次 2 步',
        '若 fast 或 fast.next 为 null → 无环，返回 null',
        '两者相遇时，把一个指针 p 指回 head，另一个 q 停在相遇点',
        'p、q 同速（每次 1 步）前进，再次相遇处即环的入口'
      ],
      code: 'public ListNode detectCycle(ListNode head) {\n    ListNode slow = head, fast = head;\n    while (true) {\n        if (fast == null || fast.next == null) return null;   // 无环\n        slow = slow.next;\n        fast = fast.next.next;\n        if (slow == fast) break;                              // 第一次相遇\n    }\n    ListNode p = head;                                        // 一个回到起点\n    while (p != slow) { p = p.next; slow = slow.next; }        // 同速前进\n    return p;                                                 // 第二次相遇 = 入口\n}',
      complexity: '时间 O(n) —— 第一次相遇不超过 n 步，第二次不超过 a 步；空间 O(1) —— 只用两个指针，比 HashSet 方案省内存。',
      pitfall: '循环里要先判 fast 和 fast.next 为空再移动，顺序反了就会 NPE。另外别把"相遇点"当成入口返回，那是两个不同的位置 —— 很多人在这一步直接 return slow 就错了。'
    },
    run: function (inp) {
      var vals = inp.vals, n = vals.length, pos = inp.pos, f = [];
      var next = [];
      for (var i = 0; i < n; i++) next[i] = (i === n - 1) ? pos : i + 1;
      function fr(note, ptr, hl) {
        return {
          note: note, vars: { slow: ptr.slow !== undefined ? ptr.slow : '-', fast: ptr.fast !== undefined ? ptr.fast : '-', 环入口: pos },
          vals: vals, next: next, ptr: ptr, hl: hl || []
        };
      }
      f.push(fr('共 ' + n + ' 个节点，尾节点连回下标 ' + pos + '（值 ' + vals[pos] + '），此处即环的入口。slow 与 fast 都从头出发。', { slow: 0, fast: 0 }, []));
      var slow = 0, fast = 0, meet = -1, guard = 0;
      while (guard++ < 4 * n + 5) {
        slow = next[slow];
        fast = next[next[fast]];
        if (slow === fast) { meet = slow; break; }
        f.push(fr('slow 走 1 步到 #' + slow + '，fast 走 2 步到 #' + fast + '，还没相遇。fast 始终比 slow 多走一倍路程，若有环必然追上。', { slow: slow, fast: fast }, []));
      }
      if (meet < 0) {
        f.push(fr('fast 走到 null，说明没有环，返回 null。', {}, []));
        return f;
      }
      f.push(fr('相遇了！slow 与 fast 在 #' + meet + '（值 ' + vals[meet] + '）相遇。注意这只是相遇点，不是入口。', { slow: meet, fast: meet }, [meet]));
      var p = 0, q = meet;
      f.push(fr('第二阶段：把 p 指回头部 #0，q 留在相遇点 #' + meet + '。两指针改为同速（每次 1 步）前进。', { p: p, q: q }, [meet]));
      var guard2 = 0;
      while (p !== q && guard2++ < n + 5) {
        p = next[p]; q = next[q];
        f.push(fr('p 走到 #' + p + '，q 走到 #' + q + '。它们还会在入口处碰头 —— 因为从头到入口的距离，恰好等于从相遇点绕回入口的距离。', { p: p, q: q }, [meet]));
      }
      f.push(fr('p 与 q 在 #' + p + '（值 ' + vals[p] + '）再次相遇 —— 这就是环的入口。', { p: p, q: q }, [p]));
      return f;
    }
  });

})();
