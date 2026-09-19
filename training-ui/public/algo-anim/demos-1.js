/* ============================================================
 * 算法动画 · 演示定义（第一批：数组 / 链表 / 栈 / 排序）
 * 每个 demo 的 run(input) 用真实算法跑一遍，逐帧记录状态。
 * 帧结构：{ note, vars, ...渲染器字段 }
 * 渲染器由 demo.render 指定：array / bars / list / stack / dp / grid / tree / graph
 * ============================================================ */
window.ALGO_DEMOS = window.ALGO_DEMOS || [];

(function () {
  var D = window.ALGO_DEMOS;

  /* ---------- 通用小工具 ---------- */
  function clone(o) { return JSON.parse(JSON.stringify(o)); }

  /* ============================================================
   * B001 两数之和 —— 哈希表
   * ============================================================ */
  D.push({
    id: 'B001', title: '两数之和', group: '数组 · 双指针 · 滑窗',
    render: 'array', star: 2,
    inputText: '2,7,11,15 | 9',
    parse: function (t) {
      var p = t.split('|');
      return { nums: p[0].split(/[,\s]+/).filter(Boolean).map(Number), target: Number(p[1]) };
    },
    desc: {
      intuition: '别用两层循环去凑。边扫边把「见过的值 → 下标」记进哈希表，扫到 x 时只要查 target−x 在不在表里就行 —— 把 O(n²) 的枚举换成 O(1) 的查表。',
      invariant: '扫描到下标 i 时，表中存放的是 nums[0..i−1] 的全部映射。因此若在表中找到 target−nums[i]，那个下标必然小于 i，答案天然有序。',
      steps: [
        '表为空，从 i=0 开始扫描',
        '对每个 x 计算补数 need = target − x',
        'need 在表中 → 返回 [表[need], i]；否则把 x→i 存入表',
        '扫完无解返回空'
      ],
      code: 'public int[] twoSum(int[] nums, int target) {\n    Map<Integer,Integer> seen = new HashMap<>();\n    for (int i = 0; i < nums.length; i++) {\n        int need = target - nums[i];\n        if (seen.containsKey(need))      // 先查后存，避免自己配自己\n            return new int[]{ seen.get(need), i };\n        seen.put(nums[i], i);\n    }\n    return new int[0];\n}',
      complexity: '时间 O(n) —— 一次扫描，每次哈希 O(1)；空间 O(n) —— 哈希表最多存 n 项。',
      pitfall: '必须先查后存。若先存，target = 2x 时会把 x 和自己配成一对，返回 [i, i] 这种非法答案。'
    },
    run: function (inp) {
      var nums = inp.nums, target = inp.target, f = [], map = {};
      function fr(note, i, hl, st, need, ans) {
        return { note: note, vars: { i: i, need: need === undefined ? '-' : need, target: target }, arr: nums, hl: hl || [], ptr: i >= 0 ? { i: i } : {}, map: clone(map), state: st || '', ans: ans };
      }
      f.push(fr('哈希表初始为空。目标 target = ' + target + '，开始逐个扫描。', -1, [], ''));
      for (var i = 0; i < nums.length; i++) {
        var need = target - nums[i];
        f.push(fr('i=' + i + '，nums[i]=' + nums[i] + '，需要补数 need = ' + target + '−' + nums[i] + ' = ' + need + '。查表…', i, [i], 'query', need));
        if (map.hasOwnProperty(need)) {
          f.push(fr('命中！补数 ' + need + ' 出现在下标 ' + map[need] + '。答案 [' + map[need] + ', ' + i + ']', i, [map[need], i], 'hit', need, [map[need], i]));
          return f;
        }
        f.push(fr('表中没有 ' + need + '，未命中。', i, [i], 'miss', need));
        map[nums[i]] = i;
        f.push(fr('把 ' + nums[i] + ' → ' + i + ' 存入表，继续。', i, [i], 'put', need));
      }
      f.push(fr('扫描结束，无解。', -1, [], 'none'));
      return f;
    }
  });

  /* ============================================================
   * B026 二分查找
   * ============================================================ */
  D.push({
    id: 'B026', title: '二分查找', group: '数组 · 双指针 · 滑窗',
    render: 'array', star: 3,
    inputText: '1,3,5,7,9,11,13 | 9',
    parse: function (t) {
      var p = t.split('|');
      return { arr: p[0].split(/[,\s]+/).filter(Boolean).map(Number), target: Number(p[1]) };
    },
    desc: {
      intuition: '有序数组给了你一个特权：只看中间那个数，就能排除掉一半。每次把搜索区间砍半，n 个元素的查找只需要 log₂n 次比较。',
      invariant: '答案若存在，必在闭区间 [l, r] 内。每轮比较后这个区间严格缩小，且依然保证这个性质。循环结束时 l > r，说明区间空了，确实不存在。',
      steps: [
        'l=0, r=n−1，取中点 m = l + (r−l)/2（防溢出）',
        'nums[m] == target → 找到',
        'nums[m] < target → 答案在右半：l = m+1',
        'nums[m] > target → 答案在左半：r = m−1'
      ],
      code: 'public int search(int[] a, int target) {\n    int l = 0, r = a.length - 1;\n    while (l <= r) {                     // 闭区间：等号不能少\n        int m = l + (r - l) / 2;         // 不用 (l+r)/2，防 int 溢出\n        if (a[m] == target) return m;\n        else if (a[m] < target) l = m + 1;\n        else r = m - 1;\n    }\n    return -1;\n}',
      complexity: '时间 O(log n)；空间 O(1)。',
      pitfall: '三个高频坑：① 中点写成 (l+r)/2，l+r 可能溢出；② 边界更新写成 l=m / r=m，当 l+1==r 时会死循环；③ while 条件用 l<r 却没处理最后一个元素。'
    },
    run: function (inp) {
      var a = inp.arr, t = inp.target, f = [];
      var l = 0, r = a.length - 1;
      function fr(note, m, extra) {
        var o = { note: note, vars: { l: l, r: r, m: m === undefined ? '-' : m, 'r-l+1': r - l + 1 }, arr: a, ptr: { l: l, r: r }, win: [l, r], hl: [], cls: {} };
        if (m !== undefined) { o.ptr.m = m; o.hl = [m]; }
        if (extra) for (var k in extra) o[k] = extra[k];
        return o;
      }
      f.push(fr('搜索区间 [l, r] = [' + l + ', ' + r + ']，共 ' + (r - l + 1) + ' 个候选。'));
      while (l <= r) {
        var m = (l + r) >> 1;
        f.push(fr('取中点 m = ' + l + ' + (' + r + '−' + l + ')/2 = ' + m + '，a[' + m + '] = ' + a[m] + '，与 ' + t + ' 比较。', m));
        if (a[m] === t) {
          f.push(fr('a[' + m + '] == ' + t + '，命中！返回 ' + m + '。', m, { cls: { 0: '' } }));
          f[f.length - 1].cls[m] = 'ok';
          return f;
        } else if (a[m] < t) {
          f.push(fr(a[m] + ' < ' + t + '，答案只可能在右侧。l = m+1 = ' + (m + 1) + '，区间砍掉左半（含 m）。', m, { cls: {} }));
          f[f.length - 1].cls[m] = 'bad';
          l = m + 1;
        } else {
          f.push(fr(a[m] + ' > ' + t + '，答案只可能在左侧。r = m−1 = ' + (m - 1) + '，区间砍掉右半（含 m）。', m, { cls: {} }));
          f[f.length - 1].cls[m] = 'bad';
          r = m - 1;
        }
        if (l <= r) f.push(fr('新区间 [l, r] = [' + l + ', ' + r + ']，剩 ' + (r - l + 1) + ' 个候选。'));
      }
      f.push({ note: 'l > r，区间已空，数组中不存在 ' + t + '。返回 −1。', vars: { l: l, r: r }, arr: a, ptr: { l: l, r: r }, hl: [], cls: {} });
      return f;
    }
  });

  /* ============================================================
   * B012 盛最多水的容器
   * ============================================================ */
  D.push({
    id: 'B012', title: '盛最多水的容器', group: '数组 · 双指针 · 滑窗',
    render: 'bars', star: 3,
    inputText: '1,8,6,2,5,4,8,3,7',
    parse: function (t) { return { h: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '容积 = 底宽 × 短板高。左右指针从两端往里夹，每次只能移动一个指针 —— 那当然要移短板那一侧：移长板宽度减 1 而高度上限不变，只会更差；移短板虽然宽减 1，但可能遇到更高的板，反而有机会变大。',
      invariant: '在 [l, r] 区间内，以 l 为左端的所有容器中，当前这个是最优的（宽度最大）。所以丢弃 l 不会漏掉答案。右侧同理。每一轮都安全地排除掉一整行/一列候选。',
      steps: [
        'l=0, r=n−1，计算 area = (r−l) × min(h[l], h[r])',
        '更新最大值',
        'h[l] < h[r] 则 l++，否则 r−−',
        'l >= r 时结束'
      ],
      code: 'public int maxArea(int[] h) {\n    int l = 0, r = h.length - 1, best = 0;\n    while (l < r) {\n        int area = (r - l) * Math.min(h[l], h[r]);\n        best = Math.max(best, area);\n        if (h[l] < h[r]) l++;            // 淘汰短板\n        else r--;\n    }\n    return best;\n}',
      complexity: '时间 O(n) —— 每个指针单向移动，共 n−1 次；空间 O(1)。',
      pitfall: '两板相等时移哪个都行（不会漏最优解，但会多走一步），写成 l++ 即可。别用双重循环枚举，O(n²) 在 n=10⁵ 上直接超时。'
    },
    run: function (inp) {
      var h = inp.h, f = [], l = 0, r = h.length - 1, best = 0;
      function fr(note, state) {
        var bars = h.map(function (v, i) {
          return { v: v, s: (state && state[i]) || (i === l || i === r ? 'active' : (i < l || i > r ? 'out' : '')) };
        });
        return { note: note, vars: { l: l, r: r, '宽 r−l': r - l, best: best }, bars: bars, ptr: { l: l, r: r }, water: [l, r, Math.min(h[l], h[r])] };
      }
      f.push(fr('左右指针贴住两端。容积 = 底宽 × 短板高。'));
      while (l < r) {
        var mh = Math.min(h[l], h[r]), area = (r - l) * mh;
        var st = {}; st[l] = 'left'; st[r] = 'right';
        var note = 'h[' + l + ']=' + h[l] + '，h[' + r + ']=' + h[r] + '。宽 ' + (r - l) + '，短板 ' + mh + '，容积 = ' + area + '。';
        var isNew = area > best;
        if (isNew) best = area;
        note += isNew ? ' 刷新最大值 → best = ' + best + '。' : ' 未超过 best = ' + best + '。';
        var mv;
        if (h[l] < h[r]) { note += '左板更短，移 l（换掉短板才可能变高）。'; mv = 'l'; l++; }
        else { note += '右板更短或相等，移 r。'; mv = 'r'; r--; }
        f.push({ note: note, vars: { l: l, r: r, best: best }, bars: h.map(function (v, i) { return { v: v, s: st[i] || (i < l || i > r ? 'out' : '') }; }), ptr: mv === 'l' ? { l: l - 1, r: r } : { l: l, r: r + 1 }, water: mv === 'l' ? [l - 1, r, mh] : [l, r + 1, mh] });
      }
      f.push(fr('l 与 r 相遇，结束。答案 ' + best + '。', {}));
      f[f.length - 1].water = null;
      return f;
    }
  });

  /* ============================================================
   * B014 无重复字符的最长子串 —— 滑动窗口
   * ============================================================ */
  D.push({
    id: 'B014', title: '无重复字符的最长子串', group: '数组 · 双指针 · 滑窗',
    render: 'array', star: 3,
    inputText: 'abcabcbb',
    parse: function (t) { return { s: t.trim() }; },
    desc: {
      intuition: '窗口 [l, r] 始终维持「内部无重复」。右端不断吃进新字符；一旦吃进的字符在窗口里已有，就把左端直接跳到那个重复位置的后一位 —— 不是一步一步挪，而是一步到位。',
      invariant: '任意时刻 [l, r] 内所有字符互不相同，且 l 是满足该条件的最小左端（对当前 r 而言）。所以窗口宽度就是「以 r 结尾的最长无重复子串」。',
      steps: [
        'l=0，用 map 记录每个字符最后出现的下标',
        'r 右移，读字符 c',
        '若 c 已在 map 且上次位置 ≥ l，说明冲突：l = last[c] + 1',
        '更新 last[c] = r，用 r−l+1 刷新答案'
      ],
      code: 'public int lengthOfLongestSubstring(String s) {\n    Map<Character,Integer> last = new HashMap<>();\n    int best = 0;\n    for (int r = 0, l = 0; r < s.length(); r++) {\n        char c = s.charAt(r);\n        if (last.containsKey(c) && last.get(c) >= l)\n            l = last.get(c) + 1;         // 一步跳到重复位之后\n        last.put(c, r);\n        best = Math.max(best, r - l + 1);\n    }\n    return best;\n}',
      complexity: '时间 O(n) —— r 和 l 都只向右移动；空间 O(min(n, 字符集))。',
      pitfall: '判断重复时必须加上 last.get(c) >= l 这个条件。否则当重复字符其实在窗口左侧之外时（比如 "abba" 的第二个 b 之后遇到 a），l 会错误地向左回退。'
    },
    run: function (inp) {
      var s = inp.s, f = [], last = {}, l = 0, best = 0;
      function fr(note, r, hl, st) {
        return {
          note: note, vars: { l: l, r: r === undefined ? '-' : r, '窗口宽': r === undefined ? 0 : r - l + 1, best: best },
          arr: s.split(''), hl: hl || [], win: r === undefined ? null : [l, r],
          ptr: r === undefined ? {} : { l: l, r: r }, map: clone(last), state: st || ''
        };
      }
      f.push(fr('窗口 [l, r] 从空开始。规则：窗口内必须无重复字符。', undefined, [], ''));
      for (var r = 0; r < s.length; r++) {
        var c = s.charAt(r);
        if (last.hasOwnProperty(c) && last[c] >= l) {
          var old = last[c];
          f.push(fr('r=' + r + ' 读到 "' + c + '"，它上次出现在 ' + old + '，且 ' + old + ' ≥ l=' + l + ' → 窗口内冲突！', r, [old, r], 'conflict'));
          l = old + 1;
          f.push(fr('左端一步跳到 ' + l + '（重复位的后一位）。窗口变为 [' + l + ', ' + r + ']。', r, [l, r], 'shrink'));
        } else {
          f.push(fr('r=' + r + ' 读到 "' + c + '"，窗口内没有它（或它已在窗口左侧外），安全，窗口右扩。', r, [r], 'grow'));
        }
        last[c] = r;
        var w = r - l + 1, isNew = w > best;
        if (isNew) best = w;
        f.push(fr('记录 "' + c + '" 最后位置 = ' + r + '。当前窗口宽 ' + w + (isNew ? '，刷新 best = ' + best : '，best 仍为 ' + best) + '。', r, [r], 'ok'));
      }
      f.push(fr('扫描结束，最长无重复子串长度 = ' + best + '。', undefined, [], 'done'));
      return f;
    }
  });

  /* ============================================================
   * B038 跳跃游戏
   * ============================================================ */
  D.push({
    id: 'B038', title: '跳跃游戏', group: '数组 · 双指针 · 滑窗',
    render: 'array', star: 2,
    inputText: '2,3,1,1,4',
    parse: function (t) { return { a: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '不关心"怎么跳"，只关心"最远能到哪"。维护一个 maxReach（当前能覆盖的最远下标），从左到右扫，只要某个位置 i 已经超出覆盖区，说明根本走不到它，直接判 false。',
      invariant: '扫描到 i 时，maxReach 是 nums[0..i−1] 全部可达位置的并集的最右端。若 i > maxReach，则 i 不可达，且 i 之后更不可能可达。',
      steps: [
        'maxReach = 0',
        '对每个 i：若 i > maxReach → 返回 false（断档）',
        '否则 maxReach = max(maxReach, i + nums[i])',
        '若 maxReach ≥ n−1 → 提前返回 true'
      ],
      code: 'public boolean canJump(int[] a) {\n    int maxReach = 0;\n    for (int i = 0; i < a.length; i++) {\n        if (i > maxReach) return false;   // 出现断档，走不过去\n        maxReach = Math.max(maxReach, i + a[i]);\n        if (maxReach >= a.length - 1) return true;\n    }\n    return true;\n}',
      complexity: '时间 O(n)；空间 O(1)。',
      pitfall: '这是贪心不是 DP。别去回溯"选哪一步"，那会退化成指数级。另外注意 nums[i] = 0 的格子不一定是死路 —— 只要 maxReach 已经越过它就行。'
    },
    run: function (inp) {
      var a = inp.a, f = [], mr = 0;
      function fr(note, i, hl) {
        return { note: note, vars: { i: i === undefined ? '-' : i, maxReach: mr, n: a.length }, arr: a, hl: hl || [], win: [0, Math.min(mr, a.length - 1)], ptr: i === undefined ? {} : { i: i }, cls: {} };
      }
      f.push(fr('maxReach = 0，表示当前最远只够到下标 0。', 0, [0]));
      for (var i = 0; i < a.length; i++) {
        if (i > mr) {
          f.push(fr('i=' + i + ' > maxReach=' + mr + '，出现断档 —— 下标 ' + i + ' 根本到不了。返回 false。', i, [i]));
          return f;
        }
        var reach = i + a[i];
        f.push(fr('i=' + i + ' 可达（' + i + ' ≤ ' + mr + '）。从这能跳到 ' + reach + '。', i, [i]));
        if (reach > mr) {
          mr = reach;
          f.push(fr('刷新 maxReach = ' + mr + '，覆盖范围向右扩展。', i, [i]));
        }
        if (mr >= a.length - 1) {
          f.push(fr('maxReach=' + mr + ' 已覆盖最后一个下标 ' + (a.length - 1) + '，可以到达终点。返回 true。', i, [a.length - 1]));
          return f;
        }
      }
      f.push(fr('扫描完毕，全程无断档。返回 true。', undefined, []));
      return f;
    }
  });

  /* ============================================================
   * B021 反转链表
   * ============================================================ */
  D.push({
    id: 'B021', title: '反转链表', group: '链表指针重连',
    render: 'list', star: 3,
    inputText: '1,2,3,4,5',
    parse: function (t) { return { vals: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '三个指针 prev / cur / next 一路向前，每次把 cur 的箭头从"指向后面"掰成"指向前面"。关键是：掰之前必须先用 next 记住后面那个节点，否则链子断了就找不回去了。',
      invariant: '循环开始时，prev 之前的部分已经全部反转完毕，且 cur 及其之后仍是原顺序。每次迭代把 cur 这一个节点从"后半"搬进"前半"。',
      steps: [
        'prev = null, cur = head',
        'next = cur.next（先存后继，防断链）',
        'cur.next = prev（掰箭头）',
        'prev = cur, cur = next（整体前移）',
        'cur == null 时，prev 就是新头'
      ],
      code: 'public ListNode reverseList(ListNode head) {\n    ListNode prev = null, cur = head;\n    while (cur != null) {\n        ListNode next = cur.next;   // 先存，别急着改\n        cur.next = prev;            // 掰箭头\n        prev = cur;\n        cur = next;\n    }\n    return prev;                    // cur 为 null 时 prev 是新头\n}',
      complexity: '时间 O(n)；空间 O(1)（迭代法，递归法栈空间 O(n)）。',
      pitfall: '顺序不能乱：先存 next 再改 cur.next。写反了链表直接断，后半段永久丢失。另外返回的是 prev 不是 cur —— 循环结束时 cur 已经是 null 了。'
    },
    run: function (inp) {
      var vals = inp.vals, n = vals.length, f = [];
      var next = []; for (var i = 0; i < n; i++) next[i] = (i === n - 1 ? -1 : i + 1);
      function fr(note, prev, cur, hl) {
        return { note: note, vars: { prev: prev === null || prev === undefined ? 'null' : '#' + prev, cur: cur === null || cur === undefined ? 'null' : '#' + cur }, vals: vals, next: next.slice(), ptr: { prev: prev === undefined ? -1 : prev, cur: cur === undefined ? -1 : cur, next: cur === undefined || cur === null || cur < 0 ? -1 : next[cur] }, hl: hl || [] };
      }
      var prev = -1, cur = 0;
      f.push(fr('初始：prev = null，cur 指向头节点 #0。准备开始逐个掰箭头。', -1, 0, [0]));
      while (cur >= 0) {
        var nxt = next[cur];
        f.push(fr('先用 next 记住 #' + cur + ' 的后继 = ' + (nxt < 0 ? 'null' : '#' + nxt) + '。不断链的关键。', prev, cur, [cur]));
        next[cur] = prev;
        f.push(fr('把 #' + cur + ' 的 next 掰向 prev（' + (prev < 0 ? 'null' : '#' + prev) + '）。这一段的箭头已反向。', prev, cur, [cur]));
        prev = cur; cur = nxt;
        f.push(fr('prev 前进到 #' + prev + '，cur 前进到 ' + (cur < 0 ? 'null' : '#' + cur) + '。' + (cur < 0 ? 'cur 为 null，循环结束。' : ''), prev, cur, cur >= 0 ? [cur] : []));
      }
      f.push(fr('cur == null，反转完成。prev = #' + prev + ' 就是新的头节点。', prev, -1, [prev]));
      return f;
    }
  });

  /* ============================================================
   * B025 删除链表倒数第 N 个节点
   * ============================================================ */
  D.push({
    id: 'B025', title: '删除倒数第 N 个节点', group: '链表指针重连',
    render: 'list', star: 3,
    inputText: '1,2,3,4,5 | 2',
    parse: function (t) {
      var p = t.split('|');
      return { vals: p[0].split(/[,\s]+/).filter(Boolean).map(Number), n: Number(p[1]) };
    },
    desc: {
      intuition: '让快指针先走 n 步，然后快慢一起走。快指针到尾时，慢指针正好停在待删节点的前一个 —— 因为两者始终隔着 n 个节点，而"尾节点的下一个"距"待删前一个"恰好是 n。',
      invariant: '快慢指针之间的间距恒为 n。当 fast 指向 null（越过尾节点）时，slow 距 null 有 n 个节点，即 slow 是倒数第 n+1 个，正是待删节点的前驱。',
      steps: [
        '加一个哑节点 dummy，指向 head（处理删头节点的情况）',
        'fast 先走 n 步',
        'fast 与 slow 同步前进，直到 fast 为 null',
        'slow.next = slow.next.next 跳过待删节点'
      ],
      code: 'public ListNode removeNthFromEnd(ListNode head, int n) {\n    ListNode dummy = new ListNode(0, head);\n    ListNode fast = dummy, slow = dummy;\n    for (int i = 0; i < n; i++) fast = fast.next;   // fast 先走 n\n    while (fast.next != null) {                     // 同步走\n        fast = fast.next; slow = slow.next;\n    }\n    slow.next = slow.next.next;                     // 跳过待删\n    return dummy.next;\n}',
      complexity: '时间 O(n) —— 一次遍历；空间 O(1)。',
      pitfall: '必须用哑节点，否则删除头节点时要单独写分支。另外同步前进的循环条件写成 fast != null 会让 slow 停在待删节点本身而不是前驱，差一位就删错了。'
    },
    run: function (inp) {
      var vals = inp.vals, k = inp.n, n = vals.length, f = [];
      var next = []; for (var i = 0; i < n; i++) next[i] = (i === n - 1 ? -1 : i + 1);
      function fr(note, fast, slow, hl, dead) {
        return {
          note: note, vars: { fast: fast < 0 ? 'null' : '#' + fast, slow: slow < 0 ? 'null' : '#' + slow, '目标': '倒数第 ' + k + ' 个' },
          vals: vals, next: next.slice(), ptr: { fast: fast, slow: slow, dummy: -2 }, hl: hl || [], dead: dead || [], dummy: true
        };
      }
      var f = [], fast = -2, slow = -2; // -2 代表 dummy 节点
      function nxt(x) { return x === -2 ? 0 : (x < 0 ? -1 : next[x]); }
      f.push(fr('dummy → head。fast 与 slow 都从 dummy 出发。', -2, -2, []));
      for (var s2 = 0; s2 < k; s2++) {
        fast = nxt(fast);
        f.push(fr('fast 先走第 ' + (s2 + 1) + ' 步 → ' + (fast < 0 ? 'null' : '#' + fast) + '。此时两指针间距 = ' + (s2 + 1) + '。', fast, slow, [fast]));
      }
      f.push(fr('fast 已领先 ' + k + ' 步。接下来两者同步前进，间距恒为 ' + k + '。', fast, slow, [fast, slow]));
      while (nxt(fast) >= 0) {
        fast = nxt(fast); slow = nxt(slow);
        f.push(fr('同步前进一步：fast = ' + (fast < 0 ? 'null' : '#' + fast) + '，slow = ' + (slow < 0 ? 'null' : '#' + slow) + '。', fast, slow, [fast, slow]));
      }
      var target = nxt(slow);
      f.push(fr('fast 已到尾节点。此时 slow 停在 #' + slow + '，它的下一个 #' + target + ' 就是倒数第 ' + k + ' 个 —— 待删节点。', fast, slow, [slow, target], [target]));
      next[slow] = nxt(target);
      f.push(fr('执行 slow.next = slow.next.next，跳过 #' + target + '。删除完成。', fast, slow, [slow], [target]));
      return f;
    }
  });

  /* ============================================================
   * B017 有效括号
   * ============================================================ */
  D.push({
    id: 'B017', title: '有效的括号', group: '栈 · 队列 · 单调栈',
    render: 'stack', star: 3,
    inputText: '({[]})',
    parse: function (t) { return { s: t.trim() }; },
    desc: {
      intuition: '括号匹配有个天然特性：最后打开的必须最先关闭。这正是栈的语义。遇到左括号就压栈，遇到右括号就弹出栈顶看是否配对。',
      invariant: '栈中存放的是"已经打开、还没关闭"的左括号，且栈顶永远是最近打开的那个。所以任何右括号都必须先跟栈顶匹配 —— 匹配不上就是非法。',
      steps: [
        '遇左括号 → push',
        '遇右括号 → 若栈空则非法；否则 pop，检查是否配对',
        '不配对 → 立即返回 false',
        '全部读完后，栈必须为空（不能有没关上的）'
      ],
      code: 'public boolean isValid(String s) {\n    Deque<Character> st = new ArrayDeque<>();\n    for (char c : s.toCharArray()) {\n        if (c == \'(\') st.push(\')\');\n        else if (c == \'[\') st.push(\']\');\n        else if (c == \'{\') st.push(\'}\');\n        else if (st.isEmpty() || st.pop() != c) return false;\n    }\n    return st.isEmpty();\n}',
      complexity: '时间 O(n)；空间 O(n) —— 最坏全是左括号。',
      pitfall: '两个收尾都不能漏：① 遇到右括号时栈可能已空（如 ")("）；② 全部读完后栈可能非空（如 "(("）。只判其中一个会漏掉一半的非法情况。技巧：压入"期望的右括号"而不是左括号，匹配时直接比较相等即可。'
    },
    run: function (inp) {
      var s = inp.s, f = [], st = [], pos = 0;
      var M = { '(': ')', '[': ']', '{': '}' };
      function fr(note, cur, action) {
        return { note: note, vars: { '当前字符': cur || '-', '栈深度': st.length }, stack: st.slice(), incoming: cur, action: action, idx: pos, src: s };
      }
      f.push(fr('栈为空。规则：左括号入栈，右括号必须与栈顶配对。', '', ''));
      for (pos = 0; pos < s.length; pos++) {
        var c = s.charAt(pos);
        if (M[c]) {
          st.push(c);
          f.push(fr('读到左括号 "' + c + '"，压栈。等待一个 "' + M[c] + '" 来关它。', c, 'push'));
        } else {
          if (st.length === 0) {
            f.push(fr('读到右括号 "' + c + '"，但栈是空的 —— 没有左括号可配。非法，返回 false。', c, 'fail'));
            return f;
          }
          var top = st[st.length - 1];
          if (M[top] === c) {
            st.pop();
            f.push(fr('读到右括号 "' + c + '"，栈顶是 "' + top + '"，需要 "' + M[top] + '" —— 配对成功，弹栈。', c, 'pop'));
          } else {
            f.push(fr('读到右括号 "' + c + '"，但栈顶 "' + top + '" 期待的是 "' + M[top] + '"。不匹配！非法，返回 false。', c, 'fail'));
            return f;
          }
        }
      }
      if (st.length === 0) f.push(fr('全部读完，栈也空了 —— 所有括号都正确配对。返回 true。', '', 'done'));
      else f.push(fr('全部读完，但栈里还剩 ' + st.length + ' 个没关上的左括号（' + st.join(' ') + '）。返回 false。', '', 'fail'));
      return f;
    }
  });

  /* ============================================================
   * A006 每日温度 —— 单调栈
   * ============================================================ */
  D.push({
    id: 'A006', title: '每日温度（单调栈）', group: '栈 · 队列 · 单调栈',
    render: 'stack', star: 3,
    inputText: '73,74,75,71,69,72,76,73',
    parse: function (t) { return { t: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '要找「右边第一个更大的」。把还没找到答案的日子按下标压入栈，并保持栈里温度单调递减。一旦新温度比栈顶高，说明栈顶那天等到了答案 —— 就是今天。弹出、算差值、继续比较，直到栈重新单调。',
      invariant: '栈中下标的对应温度严格单调递减。因此新来的温度只要高于栈顶，就一定是栈顶那天的答案（它是右边第一个打破递减的元素）。',
      steps: [
        '栈存下标（不存温度，因为要算距离）',
        '对每天 i：while 栈非空 且 T[i] > T[栈顶] → 弹出 j，ans[j] = i − j',
        '把 i 压栈',
        '剩下的栈内元素答案都是 0（后面没有更暖的）'
      ],
      code: 'public int[] dailyTemperatures(int[] T) {\n    int[] ans = new int[T.length];\n    Deque<Integer> st = new ArrayDeque<>();   // 存下标，单调递减\n    for (int i = 0; i < T.length; i++) {\n        while (!st.isEmpty() && T[i] > T[st.peek()]) {\n            int j = st.pop();\n            ans[j] = i - j;                   // 等到答案了\n        }\n        st.push(i);\n    }\n    return ans;                               // 未弹出的默认 0\n}',
      complexity: '时间 O(n) —— 每个下标最多入栈一次、出栈一次（均摊）；空间 O(n)。',
      pitfall: '栈里存下标而不是温度，否则算不出天数差。另外这是"均摊 O(n)"，虽然看起来有 while 嵌套 —— 别误判成 O(n²)。'
    },
    run: function (inp) {
      var T = inp.t, n = T.length, f = [], st = [], ans = new Array(n).fill(0);
      function fr(note, i, action) {
        return {
          note: note, vars: { i: i === undefined ? '-' : i, 'T[i]': i === undefined ? '-' : T[i], '栈顶': st.length ? '#' + st[st.length - 1] + '(' + T[st[st.length - 1]] + ')' : '空' },
          stack: st.map(function (x) { return { v: T[x], sub: '#' + x }; }), bars: T, cur: i, ans: ans.slice(), action: action
        };
      }
      f.push(fr('栈为空。栈里存放「还没等到更暖一天」的下标，保持温度单调递减。', undefined, ''));
      for (var i = 0; i < n; i++) {
        f.push(fr('第 ' + i + ' 天，温度 ' + T[i] + '。先看能不能给栈里那些日子一个答案。', i, 'probe'));
        while (st.length && T[i] > T[st[st.length - 1]]) {
          var j = st[st.length - 1];
          f.push(fr(T[i] + ' > 栈顶 #' + j + ' 的 ' + T[j] + '，#' + j + ' 等到答案了：ans[' + j + '] = ' + i + ' − ' + j + ' = ' + (i - j) + '。', i, 'compare'));
          st.pop(); ans[j] = i - j;
          f.push(fr('弹出 #' + j + '。' + (st.length ? '继续跟新栈顶比较。' : '栈空了。'), i, 'pop'));
        }
        st.push(i);
        f.push(fr('把第 ' + i + ' 天压栈，栈内温度保持单调递减。', i, 'push'));
      }
      f.push(fr('扫描结束。栈里剩下的下标后面没有更暖的日子，答案保持 0。最终 ans = [' + ans.join(', ') + ']', undefined, 'done'));
      return f;
    }
  });

  /* ============================================================
   * B030 快速排序 partition
   * ============================================================ */
  D.push({
    id: 'B030', title: '快速排序 partition', group: '排序 · 堆 · 快速选择',
    render: 'bars', star: 3,
    inputText: '5,3,8,4,2,7,1,10',
    parse: function (t) { return { a: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: '选一个基准 pivot，把比它小的都扔左边、大的扔右边。这一步叫 partition。做完之后 pivot 的位置就是它最终应该在的位置 —— 左右两半再各自递归，就有序了。',
      invariant: '扫描过程中始终维持：[l+1 .. i] 全部 < pivot，[i+1 .. r−1] 全部 ≥ pivot。r 是正在考察的元素。每次发现一个小于 pivot 的，就把区间边界 i 右扩一格并与之交换。',
      steps: [
        '取最右元素为 pivot',
        'i = l−1（小于区右界），r 从 l 扫到 end−1',
        'a[r] < pivot → i++，交换 a[i] 与 a[r]',
        '扫描结束后交换 a[i+1] 与 pivot，返回 i+1'
      ],
      code: 'int partition(int[] a, int l, int r) {\n    int pivot = a[r], i = l - 1;\n    for (int j = l; j < r; j++)\n        if (a[j] < pivot) { i++; swap(a, i, j); }\n    swap(a, i + 1, r);\n    return i + 1;                    // pivot 的最终位置\n}\nvoid quickSort(int[] a, int l, int r) {\n    if (l >= r) return;\n    int p = partition(a, l, r);\n    quickSort(a, l, p - 1);\n    quickSort(a, p + 1, r);\n}',
      complexity: '平均 O(n log n)，最坏 O(n²)（已排序时每次划分极度不平衡）；空间 O(log n) 递归栈。',
      pitfall: 'pivot 取最右元素时，如果数组已经有序，每次划分都是 1 : n−1，直接退化成 O(n²)。实用做法是随机取 pivot 或三数取中。另外注意循环里是 a[j] < pivot 而不是 ≤，用 ≤ 在大量重复元素时也会退化。'
    },
    run: function (inp) {
      var a = inp.a.slice(), f = [], L = 0, R = a.length - 1;
      function swap(i, j) { var t = a[i]; a[i] = a[j]; a[j] = t; }
      function fr(note, states, ptr, piv) {
        return {
          note: note, vars: { l: L, r: R, pivot: piv === undefined ? a[R] : piv },
          bars: a.map(function (v, i) { return { v: v, s: states && states[i] ? states[i] : '' }; }),
          ptr: ptr || {}
        };
      }
      var pivot = a[R], i = L - 1;
      var st = {}; st[R] = 'pivot';
      f.push(fr('选最右元素 ' + pivot + ' 作为 pivot。i = l−1 = ' + i + '（「小于区」的右界，初始为空）。', st, {}, pivot));
      for (var j = L; j < R; j++) {
        st = {}; st[R] = 'pivot'; if (i >= L) st[i] = 'bound';
        f.push(fr('j=' + j + '，检查 a[j]=' + a[j] + ' 是否 < pivot(' + pivot + ')。', Object.assign({}, st, { [j]: 'scan' }), { j: j, i: i }, pivot));
        if (a[j] < pivot) {
          i++;
          swap(i, j);
          f.push(fr(a[j] + ' < ' + pivot + ' → i 右扩到 ' + i + '，并交换 a[' + i + '] 与 a[' + j + ']。小于区长大一格。', Object.assign({}, { [i]: 'ok', [R]: 'pivot' }), { j: j, i: i }, pivot));
        } else {
          f.push(fr(a[j] + ' ≥ ' + pivot + '，跳过。它留在右侧。', Object.assign({}, st, { [j]: 'skip' }), { j: j, i: i }, pivot));
        }
      }
      swap(i + 1, R);
      st = {}; st[i + 1] = 'done';
      f.push(fr('扫描结束，把 pivot 换到 i+1 = ' + (i + 1) + '。左侧全 < ' + pivot + '，右侧全 ≥ ' + pivot + '。', st, { p: i + 1 }, pivot));
      f.push(fr('partition 完成，返回 ' + (i + 1) + '。这个位置已经定了，左右两半各自递归即可。', st, { p: i + 1 }, pivot));
      return f;
    }
  });

  /* ============================================================
   * A008 滑动窗口最大值 —— 单调队列
   * ============================================================ */
  D.push({
    id: 'A008', title: '滑动窗口最大值', group: '栈 · 队列 · 单调栈',
    render: 'array', star: 3,
    inputText: '1,3,-1,-3,5,3,6,7 | 3',
    parse: function (t) {
      var p = t.split('|');
      return { a: p[0].split(/[,\s]+/).filter(Boolean).map(Number), k: Number(p[1]) };
    },
    desc: {
      intuition: '窗口滑动时如果每次重新扫一遍求最大，是 O(nk)。改用双端队列存"候选最大值的下标"，并保持队列里对应的值单调递减 —— 队首永远是当前窗口的最大值。',
      invariant: '队列中下标递增、对应值严格递减。队首是窗口内最大值。新元素入队前，先把队尾所有比它小的元素弹出 —— 那些元素既比它小、又比它先过期，永远不会成为答案。',
      steps: [
        '遍历 i：先弹出队尾所有值 < a[i] 的元素',
        '把 i 入队尾',
        '弹出队首中已滑出窗口的下标（< i−k+1）',
        'i ≥ k−1 时，队首即为窗口最大值'
      ],
      code: 'public int[] maxSlidingWindow(int[] a, int k) {\n    Deque<Integer> dq = new ArrayDeque<>();   // 存下标，值单调递减\n    int[] res = new int[a.length - k + 1];\n    for (int i = 0; i < a.length; i++) {\n        while (!dq.isEmpty() && a[dq.peekLast()] < a[i]) dq.pollLast();\n        dq.offerLast(i);\n        if (dq.peekFirst() <= i - k) dq.pollFirst();   // 过期出队\n        if (i >= k - 1) res[i - k + 1] = a[dq.peekFirst()];\n    }\n    return res;\n}',
      complexity: '时间 O(n) —— 每个下标入队出队各一次；空间 O(k)。',
      pitfall: '队列存下标而不是值，否则无法判断队首是否已滑出窗口。另外"先弹队尾、再入队、最后弹过期队首"这个顺序不能颠倒。'
    },
    run: function (inp) {
      var a = inp.a, k = inp.k, f = [], dq = [], res = [];
      function fr(note, i, hl) {
        return {
          note: note, vars: { i: i === undefined ? '-' : i, '队首': dq.length ? '#' + dq[0] + '(' + a[dq[0]] + ')' : '空' },
          arr: a, win: i === undefined || i < k - 1 ? null : [i - k + 1, i],
          hl: hl || [], ptr: i === undefined ? {} : { i: i },
          queue: dq.map(function (x) { return { v: a[x], sub: '#' + x }; }),
          res: res.slice(), k: k
        };
      }
      f.push(fr('窗口大小 k=' + k + '。双端队列存下标，保持对应值单调递减，队首即最大值。', undefined, []));
      for (var i = 0; i < a.length; i++) {
        f.push(fr('i=' + i + '，a[i]=' + a[i] + '。先把队尾所有 < ' + a[i] + ' 的候选淘汰（它们又小又老，永远轮不到）。', i, [i]));
        while (dq.length && a[dq[dq.length - 1]] < a[i]) {
          var rm = dq.pop();
          f.push(fr('淘汰 #' + rm + '(' + a[rm] + ')：它比 ' + a[i] + ' 小，且更早过期。', i, [i]));
        }
        dq.push(i);
        f.push(fr('把 #' + i + '(' + a[i] + ') 入队尾。', i, [i]));
        if (dq.length && dq[0] <= i - k) {
          var ex = dq.shift();
          f.push(fr('队首 #' + ex + ' 已滑出窗口（≤ ' + (i - k) + '），出队。', i, [i]));
        }
        if (i >= k - 1) {
          res.push(a[dq[0]]);
          f.push(fr('窗口 [' + (i - k + 1) + ', ' + i + '] 已满，最大值 = 队首 #' + dq[0] + ' = ' + a[dq[0]] + '。', i, [dq[0], i]));
        } else {
          f.push(fr('窗口还没填满（需到 i=' + (k - 1) + '），暂不输出。', i, [i]));
        }
      }
      f.push(fr('完成。结果 = [' + res.join(', ') + ']', undefined, []));
      return f;
    }
  });

})();
