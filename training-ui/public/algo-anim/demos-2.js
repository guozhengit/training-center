/* ============================================================
 * 算法动画 · 演示定义（第二批：DP / 网格 / 树 / 图）
 * ============================================================ */
window.ALGO_DEMOS = window.ALGO_DEMOS || [];

(function () {
  var D = window.ALGO_DEMOS;

  /* ============================================================
   * A029 最长公共子序列 LCS
   * ============================================================ */
  D.push({
    id: 'A029', title: '最长公共子序列', group: 'DP 表格填充',
    render: 'dp', star: 3,
    inputText: 'abcde | ace',
    parse: function (t) {
      var p = t.split('|');
      return { a: p[0].trim(), b: p[1].trim() };
    },
    desc: {
      intuition: '两个字符串各占一维，填满一张表。dp[i][j] 表示「A 的前 i 个、B 的前 j 个字符」的最长公共子序列长度。每格只需看左边、上边、左上角三个已知格子。',
      invariant: '填 dp[i][j] 时，它依赖的 dp[i−1][j]、dp[i][j−1]、dp[i−1][j−1] 都已计算完毕（按行从上到下、每行从左到右填）。这就是"无后效性"。',
      steps: [
        '第 0 行、第 0 列全为 0（空串与任何串的 LCS 是 0）',
        'a[i−1] == b[j−1] → dp[i][j] = dp[i−1][j−1] + 1（字符匹配，长度 +1）',
        '不等 → dp[i][j] = max(dp[i−1][j], dp[i][j−1])（跳过其中一个字符）',
        '右下角即答案；沿来源箭头回溯可还原具体子序列'
      ],
      code: 'public int lcs(String a, String b) {\n    int m = a.length(), n = b.length();\n    int[][] dp = new int[m + 1][n + 1];\n    for (int i = 1; i <= m; i++)\n        for (int j = 1; j <= n; j++) {\n            if (a.charAt(i - 1) == b.charAt(j - 1))\n                dp[i][j] = dp[i - 1][j - 1] + 1;\n            else\n                dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);\n        }\n    return dp[m][n];\n}',
      complexity: '时间 O(mn)；空间 O(mn)，可压成一维数组 O(n)（但那样就无法回溯路径了）。',
      pitfall: '字符串下标与 DP 表下标差 1：dp[i][j] 对应 a.charAt(i−1) 和 b.charAt(j−1)。这是最常见的越界来源。另外"子序列"不要求连续，跟"子串"是两回事。'
    },
    run: function (inp) {
      var A = inp.a, B = inp.b, m = A.length, n = B.length, f = [];
      var dp = [];
      for (var i = 0; i <= m; i++) dp.push(new Array(n + 1).fill(0));
      function fr(note, cur, src) {
        return {
          note: note, vars: { i: cur ? cur[0] : '-', j: cur ? cur[1] : '-', 'A[i-1]': cur && cur[0] > 0 ? A[cur[0] - 1] : '-', 'B[j-1]': cur && cur[1] > 0 ? B[cur[1] - 1] : '-' },
          rows: JSON.parse(JSON.stringify(dp)), rl: ('-' + A).split(''), cl: ('-' + B).split(''),
          cur: cur || null, src: src || []
        };
      }
      f.push(fr('建表 (m+1) × (n+1)。第 0 行第 0 列全为 0 —— 空串与任何串的公共子序列长度都是 0。', null));
      for (var i2 = 1; i2 <= m; i2++) {
        for (var j2 = 1; j2 <= n; j2++) {
          if (A[i2 - 1] === B[j2 - 1]) {
            f.push(fr('A[' + (i2 - 1) + ']="' + A[i2 - 1] + '" == B[' + (j2 - 1) + ']="' + B[j2 - 1] + '"，字符匹配。看左上角 dp[' + (i2 - 1) + '][' + (j2 - 1) + ']=' + dp[i2 - 1][j2 - 1] + '。', [i2, j2], [[i2 - 1, j2 - 1]]));
            dp[i2][j2] = dp[i2 - 1][j2 - 1] + 1;
            f.push(fr('dp[' + i2 + '][' + j2 + '] = ' + dp[i2 - 1][j2 - 1] + ' + 1 = ' + dp[i2][j2] + '。匹配字符接进公共子序列，长度 +1。', [i2, j2], [[i2 - 1, j2 - 1]]));
          } else {
            f.push(fr('A[' + (i2 - 1) + ']="' + A[i2 - 1] + '" ≠ B[' + (j2 - 1) + ']="' + B[j2 - 1] + '"，不匹配。看上方 ' + dp[i2 - 1][j2] + ' 与左方 ' + dp[i2][j2 - 1] + '。', [i2, j2], [[i2 - 1, j2], [i2, j2 - 1]]));
            dp[i2][j2] = Math.max(dp[i2 - 1][j2], dp[i2][j2 - 1]);
            f.push(fr('取较大者：dp[' + i2 + '][' + j2 + '] = max(' + dp[i2 - 1][j2] + ', ' + dp[i2][j2 - 1] + ') = ' + dp[i2][j2] + '。相当于"跳过 A 的这个字符"或"跳过 B 的这个字符"。', [i2, j2], [[i2 - 1, j2], [i2, j2 - 1]]));
          }
        }
      }
      f.push(fr('填表完成。右下角 dp[' + m + '][' + n + '] = ' + dp[m][n] + ' 即最长公共子序列长度。', [m, n], []));
      return f;
    }
  });

  /* ============================================================
   * A030 编辑距离
   * ============================================================ */
  D.push({
    id: 'A030', title: '编辑距离', group: 'DP 表格填充',
    render: 'dp', star: 3,
    inputText: 'horse | ros',
    parse: function (t) {
      var p = t.split('|');
      return { a: p[0].trim(), b: p[1].trim() };
    },
    desc: {
      intuition: 'dp[i][j] = 把 A 的前 i 个字符变成 B 的前 j 个字符的最少操作次数。对最后一格，只有三种可能得来：删一个（从左边来）、插一个（从上边来）、替换或不变（从左上角来）。取最小的那个 +1。',
      invariant: 'dp[i][j] 只依赖左、上、左上三个已填格子。第 0 行表示"把空串变成 B 的前 j 个"需要 j 次插入，第 0 列表示"把 A 的前 i 个变成空串"需要 i 次删除。',
      steps: [
        '初始化 dp[i][0] = i（全删），dp[0][j] = j（全插）',
        '字符相等 → dp[i][j] = dp[i−1][j−1]（不用动）',
        '不等 → 1 + min(左=删, 上=插, 左上=替换)',
        '右下角为最少编辑次数'
      ],
      code: 'public int minDistance(String a, String b) {\n    int m = a.length(), n = b.length();\n    int[][] dp = new int[m + 1][n + 1];\n    for (int i = 0; i <= m; i++) dp[i][0] = i;\n    for (int j = 0; j <= n; j++) dp[0][j] = j;\n    for (int i = 1; i <= m; i++)\n        for (int j = 1; j <= n; j++) {\n            if (a.charAt(i - 1) == b.charAt(j - 1))\n                dp[i][j] = dp[i - 1][j - 1];\n            else\n                dp[i][j] = 1 + Math.min(dp[i - 1][j],      // 删\n                             Math.min(dp[i][j - 1],        // 插\n                                      dp[i - 1][j - 1]));  // 替换\n        }\n    return dp[m][n];\n}',
      complexity: '时间 O(mn)；空间 O(mn)，可优化到 O(min(m,n))。',
      pitfall: '第 0 行第 0 列的初始化不能漏 —— 它们代表"全插入"和"全删除"这类边界情况。另外"替换"是从左上角 +1，而"不变"是左上角 +0，两者共用同一个来源格子，别混淆。'
    },
    run: function (inp) {
      var A = inp.a, B = inp.b, m = A.length, n = B.length, f = [];
      var dp = [];
      for (var i = 0; i <= m; i++) dp.push(new Array(n + 1).fill(0));
      for (var x = 0; x <= m; x++) dp[x][0] = x;
      for (var y = 0; y <= n; y++) dp[0][y] = y;
      function fr(note, cur, src) {
        return {
          note: note, vars: { i: cur ? cur[0] : '-', j: cur ? cur[1] : '-' },
          rows: JSON.parse(JSON.stringify(dp)), rl: ('-' + A).split(''), cl: ('-' + B).split(''),
          cur: cur || null, src: src || []
        };
      }
      f.push(fr('第 0 列 = 0,1,2…（把 A 的前 i 个全删掉），第 0 行 = 0,1,2…（从空串插入出 B 的前 j 个）。', null));
      for (var i2 = 1; i2 <= m; i2++) {
        for (var j2 = 1; j2 <= n; j2++) {
          if (A[i2 - 1] === B[j2 - 1]) {
            f.push(fr('A[' + (i2 - 1) + '] == B[' + (j2 - 1) + '] = "' + A[i2 - 1] + '"，字符相同，无需操作。', [i2, j2], [[i2 - 1, j2 - 1]]));
            dp[i2][j2] = dp[i2 - 1][j2 - 1];
            f.push(fr('dp[' + i2 + '][' + j2 + '] = dp[' + (i2 - 1) + '][' + (j2 - 1) + '] = ' + dp[i2][j2] + '（直接继承，不加 1）。', [i2, j2], [[i2 - 1, j2 - 1]]));
          } else {
            var del = dp[i2 - 1][j2], ins = dp[i2][j2 - 1], rep = dp[i2 - 1][j2 - 1];
            f.push(fr('A[' + (i2 - 1) + ']="' + A[i2 - 1] + '" ≠ B[' + (j2 - 1) + ']="' + B[j2 - 1] + '"。三个来源：删=' + del + '（上）、插=' + ins + '（左）、替换=' + rep + '（左上）。', [i2, j2], [[i2 - 1, j2], [i2, j2 - 1], [i2 - 1, j2 - 1]]));
            dp[i2][j2] = 1 + Math.min(del, ins, rep);
            f.push(fr('取最小 ' + Math.min(del, ins, rep) + ' 再 +1 → dp[' + i2 + '][' + j2 + '] = ' + dp[i2][j2] + '。', [i2, j2], [[i2 - 1, j2], [i2, j2 - 1], [i2 - 1, j2 - 1]]));
          }
        }
      }
      f.push(fr('填表完成。最少编辑次数 = dp[' + m + '][' + n + '] = ' + dp[m][n] + '。', [m, n], []));
      return f;
    }
  });

  /* ============================================================
   * B040 最大子数组和 —— Kadane
   * ============================================================ */
  D.push({
    id: 'B040', title: '最大子数组和（Kadane）', group: 'DP 表格填充',
    render: 'bars', star: 3,
    inputText: '-2,1,-3,4,-1,2,1,-5,4',
    parse: function (t) { return { a: t.split(/[,\s]+/).filter(Boolean).map(Number) }; },
    desc: {
      intuition: 'cur = 「以当前位置结尾的最大子数组和」。对每个新元素 x，只有两种选择：把 x 接在前面那段后面（cur + x），或者从 x 重新开始（x）。哪个大选哪个。全程用 best 记下出现过的最大 cur。',
      invariant: 'cur 始终表示"必须以 i 结尾"的最大和 —— 注意是"必须包含 a[i]"，这个约束让状态转移只有两种可能，是 DP 能成立的关键。best = max(所有历史 cur)。',
      steps: [
        'cur = best = a[0]',
        '对每个 x：cur = max(x, cur + x)',
        'best = max(best, cur)',
        '返回 best'
      ],
      code: 'public int maxSubArray(int[] a) {\n    int cur = a[0], best = a[0];\n    for (int i = 1; i < a.length; i++) {\n        cur = Math.max(a[i], cur + a[i]);   // 接上 vs 重开\n        best = Math.max(best, cur);\n    }\n    return best;\n}',
      complexity: '时间 O(n)；空间 O(1)。',
      pitfall: '初始值必须是 a[0] 而不是 0 —— 全负数数组用 0 初始化会返回 0，而正确答案是那个最大的负数。另外 cur 的含义是"以 i 结尾"，不是"到 i 为止"，不要试图直接用它当答案。'
    },
    run: function (inp) {
      var a = inp.a, f = [];
      var cur = a[0], best = a[0];
      function fr(note, i, seg) {
        return {
          note: note, vars: { i: i, 'a[i]': a[i], cur: cur, best: best },
          bars: a.map(function (v, k) { return { v: v, s: k === i ? 'active' : (seg && k >= seg[0] && k <= seg[1] ? 'win' : (k < i ? 'done' : '')) }; }),
          ptr: { i: i }, win: seg || null
        };
      }
      f.push(fr('初始化：cur = best = a[0] = ' + a[0] + '。cur 的含义是"必须以当前位置结尾的最大子数组和"。', 0, [0, 0]));
      var start = 0;
      for (var i = 1; i < a.length; i++) {
        var keep = cur + a[i], restart = a[i];
        f.push(fr('i=' + i + '，a[i]=' + a[i] + '。两条路：接在后面 = cur + a[i] = ' + cur + ' + ' + a[i] + ' = ' + keep + '；从这儿重开 = ' + restart + '。', i, [start, i - 1]));
        if (keep > restart) {
          cur = keep;
          f.push(fr(keep + ' > ' + restart + '，接上更划算。cur = ' + cur + '，子数组仍从 #' + start + ' 开始。', i, [start, i]));
        } else {
          cur = restart; start = i;
          f.push(fr(restart + ' ≥ ' + keep + '（前面的拖后腿了），干脆从 #' + i + ' 重新开始。cur = ' + cur + '。', i, [i, i]));
        }
        if (cur > best) { best = cur; f.push(fr('cur 超过历史最好成绩，刷新 best = ' + best + '。', i, [start, i])); }
        else f.push(fr('cur = ' + cur + ' 未超过 best = ' + best + '，best 保持不变。', i, [start, i]));
      }
      f.push(fr('扫描结束。答案 best = ' + best + '。', a.length - 1, [start, a.length - 1]));
      return f;
    }
  });

  /* ============================================================
   * A019 岛屿数量
   * ============================================================ */
  D.push({
    id: 'A019', title: '岛屿数量', group: '网格 · 棋盘',
    render: 'grid', star: 3,
    inputText: '1,1,0,0,0\n1,1,0,0,0\n0,0,1,0,0\n0,0,0,1,1',
    parse: function (t) {
      return { g: t.trim().split(/\n+/).map(function (r) { return r.split(/[,\s]+/).filter(Boolean).map(Number); }) };
    },
    desc: {
      intuition: '从左到右、从上到下扫格子。碰到一块陆地就计数 +1，然后立刻做一次洪水填充（DFS/BFS）把与它相连的所有陆地都标记成"已访问"。这样下次扫到它们时就不会重复计数。',
      invariant: '扫描过程中，每个已被访问的陆地块都属于某个已编号的岛屿。因此新遇到的未访问陆地，必然属于一个全新的岛屿 —— 否则它早就被之前的洪水填充淹掉了。',
      steps: [
        '双重循环遍历每个格子',
        '遇到 grid[i][j] == "1" → 计数 +1，从该格开始 DFS',
        'DFS：把当前格置 0，再递归上下左右四个方向',
        '越界或遇到 0 就返回'
      ],
      code: 'public int numIslands(char[][] g) {\n    int cnt = 0;\n    for (int i = 0; i < g.length; i++)\n        for (int j = 0; j < g[0].length; j++)\n            if (g[i][j] == \'1\') { cnt++; dfs(g, i, j); }\n    return cnt;\n}\nvoid dfs(char[][] g, int i, int j) {\n    if (i < 0 || i >= g.length || j < 0 || j >= g[0].length) return;\n    if (g[i][j] != \'1\') return;\n    g[i][j] = \'0\';                         // 就地标记已访问\n    dfs(g, i + 1, j); dfs(g, i - 1, j);\n    dfs(g, i, j + 1); dfs(g, i, j - 1);\n}',
      complexity: '时间 O(mn) —— 每格最多被访问一次；空间 O(mn) 最坏递归深度。',
      pitfall: '必须"就地置 0"而不是另开 visited 数组再判断顺序 —— 就地标记能天然避免同一个格子被重复入栈。另外递归深度在 200×200 的网格上可能栈溢出，生产环境建议改用显式栈的 BFS。'
    },
    run: function (inp) {
      var g = inp.g.map(function (r) { return r.slice(); });
      var R = g.length, C = g[0].length, f = [], cnt = 0;
      function fr(note, cur, flood, stack) {
        return {
          note: note, vars: { 岛屿数: cnt, R: R, C: C },
          grid: g.map(function (r) { return r.slice(); }), cur: cur || null,
          flood: flood || [], stack: stack || []
        };
      }
      f.push(fr('从左上角开始逐行扫描。规则：遇到值为 1 的未访问陆地 → 岛屿数 +1，然后洪水填充淹掉整片。', null, []));
      for (var i = 0; i < R; i++) {
        for (var j = 0; j < C; j++) {
          if (g[i][j] === 0) {
            f.push(fr('(' + i + ',' + j + ') 是水，跳过。', [i, j], []));
            continue;
          }
          cnt++;
          f.push(fr('(' + i + ',' + j + ') 是未访问的陆地！发现第 ' + cnt + ' 座岛屿，从这儿开始洪水填充。', [i, j], []));
          var st = [[i, j]], visited = [];
          while (st.length) {
            var p = st[st.length - 1];
            var x = p[0], y = p[1];
            if (x < 0 || x >= R || y < 0 || y >= C || g[x][y] === 0) {
              st.pop();
              if (x >= 0 && x < R && y >= 0 && y < C) f.push(fr('(' + x + ',' + y + ') 是水或已淹没，回溯。', [x, y], visited.slice(), st.slice()));
              continue;
            }
            g[x][y] = 0; visited.push([x, y]);
            f.push(fr('淹没 (' + x + ',' + y + ')，把它归入第 ' + cnt + ' 座岛。接着探四个邻居。', [x, y], visited.slice(), st.slice()));
            st.push([x + 1, y]); st.push([x - 1, y]); st.push([x, y + 1]); st.push([x, y - 1]);
          }
          f.push(fr('第 ' + cnt + ' 座岛填充完毕，共淹没 ' + visited.length + ' 格。回到主扫描。', null, visited.slice(), []));
        }
      }
      f.push(fr('扫描结束。岛屿总数 = ' + cnt + '。', null, [], []));
      return f;
    }
  });

  /* ============================================================
   * A013 N 皇后
   * ============================================================ */
  D.push({
    id: 'A013', title: 'N 皇后', group: '网格 · 棋盘',
    render: 'grid', star: 3,
    inputText: '4',
    parse: function (t) { return { n: Number(t.trim()) }; },
    desc: {
      intuition: '一行一行地放皇后。放在某列前先检查：这一列有没有皇后、两条对角线上有没有皇后。冲突就试下一列；这一行所有列都冲突，就退回上一行挪动那个皇后 —— 标准的回溯。',
      invariant: '递归到第 r 行时，前 r−1 行已放好且互不攻击。用三个集合（列、主对角线、副对角线）把"合法性检查"从 O(n) 降到 O(1)：主对角线的标识是 r−c，副对角线是 r+c。',
      steps: [
        '从第 0 行开始，逐列尝试放皇后',
        '检查 col / diag1(r−c) / diag2(r+c) 三个集合',
        '合法 → 放子、加入集合、递归下一行',
        '递归返回后撤销（回溯），试下一列'
      ],
      code: 'Set<Integer> col = new HashSet<>(), d1 = new HashSet<>(), d2 = new HashSet<>();\nvoid backtrack(int r, int n, List<char[]> board, List<List<String>> ans) {\n    if (r == n) { ans.add(snapshot(board)); return; }\n    for (int c = 0; c < n; c++) {\n        if (col.contains(c) || d1.contains(r - c) || d2.contains(r + c)) continue;\n        board[r][c] = \'Q\';\n        col.add(c); d1.add(r - c); d2.add(r + c);\n        backtrack(r + 1, n, board, ans);\n        board[r][c] = \'.\';                      // 撤销\n        col.remove(c); d1.remove(r - c); d2.remove(r + c);\n    }\n}',
      complexity: '时间 O(n!)，实际远小于（剪枝很有效）；空间 O(n) 递归栈 + 三个集合。',
      pitfall: '撤销那一步（board[r][c] = \'.\' 和三个 remove）最容易漏，漏了就再也搜不到其他解。另外对角线的标识 r−c 可能为负，HashSet 存负数没问题，但如果你改用 boolean 数组就得加 n−1 偏移。'
    },
    run: function (inp) {
      var n = inp.n, f = [], sols = [];
      var board = [];
      for (var i = 0; i < n; i++) board.push(new Array(n).fill(0));
      var col = {}, d1 = {}, d2 = {};
      function fr(note, r, c, mark) {
        return {
          note: note, vars: { 行: r === undefined ? '-' : r, 列: c === undefined ? '-' : c, 已找到解: sols.length },
          grid: board.map(function (row) { return row.slice(); }),
          cur: (r !== undefined && c !== undefined && c < n) ? [r, c] : null,
          mark: mark || null, n: n, sols: sols.length
        };
      }
      f.push(fr('棋盘 ' + n + '×' + n + '。逐行放皇后，用「列 + 两条对角线」三个集合做 O(1) 冲突检测。', 0, 0));
      function bt(r) {
        if (r === n) {
          sols.push(1);
          f.push(fr('第 ' + r + ' 行 = 最后一行之后 → 找到一个完整解！（第 ' + sols.length + ' 个）', r - 1, n));
          return;
        }
        for (var c = 0; c < n; c++) {
          if (col[c] || d1[r - c] || d2[r + c]) {
            var why = col[c] ? '第 ' + c + ' 列已有皇后' : (d1[r - c] ? '主对角线 (r−c=' + (r - c) + ') 被占' : '副对角线 (r+c=' + (r + c) + ') 被占');
            f.push(fr('尝试 (' + r + ',' + c + ')：冲突 —— ' + why + '。换下一列。', r, c, { r: r, c: c, t: 'x' }));
            continue;
          }
          board[r][c] = 1; col[c] = 1; d1[r - c] = 1; d2[r + c] = 1;
          f.push(fr('(' + r + ',' + c + ') 安全，放下皇后。三个集合记录占用，进入第 ' + (r + 1) + ' 行。', r, c, { r: r, c: c, t: 'Q' }));
          bt(r + 1);
          board[r][c] = 0; delete col[c]; delete d1[r - c]; delete d2[r + c];
          f.push(fr('回溯：撤销 (' + r + ',' + c + ') 的皇后，尝试本行下一列。', r, c, null));
        }
        f.push(fr('第 ' + r + ' 行所有列都试过了，回到上一行。', r, n));
      }
      bt(0);
      f.push(fr('搜索结束。' + n + ' 皇后共有 ' + sols.length + ' 种解法。', undefined, undefined));
      return f;
    }
  });

  /* ============================================================
   * B032 二叉树层序遍历
   * ============================================================ */
  D.push({
    id: 'B032', title: '二叉树层序遍历', group: '树 · 遍历递归',
    render: 'tree', star: 3,
    inputText: '3,9,20,null,null,15,7',
    parse: function (t) {
      return { a: t.split(/[,\s]+/).filter(Boolean).map(function (x) { return x === 'null' ? null : Number(x); }) };
    },
    desc: {
      intuition: '递归遍历是"一路走到底再回来"，靠的是函数调用栈。层序遍历要的是"一层一层横着走"，那就自己拿一个队列：先把根放进去，每次取出一个节点，就把它的左右孩子追加到队尾 —— 先进先出天然保证了按层输出。',
      invariant: '队列中始终存放着「当前层的剩余节点 + 下一层已发现的节点」，且按从左到右排列。每次循环前记录队列长度 size，就能精确地把当前层一次性切出来。',
      steps: [
        '根入队',
        'while 队列非空：记 size = 队列长度（当前层节点数）',
        '循环 size 次：出队一个，访问它，把非空孩子入队',
        '这 size 个就是一整层'
      ],
      code: 'public List<List<Integer>> levelOrder(TreeNode root) {\n    List<List<Integer>> ans = new ArrayList<>();\n    if (root == null) return ans;\n    Queue<TreeNode> q = new ArrayDeque<>();\n    q.offer(root);\n    while (!q.isEmpty()) {\n        int size = q.size();              // 关键：先固定当前层的宽度\n        List<Integer> level = new ArrayList<>();\n        for (int i = 0; i < size; i++) {\n            TreeNode node = q.poll();\n            level.add(node.val);\n            if (node.left != null) q.offer(node.left);\n            if (node.right != null) q.offer(node.right);\n        }\n        ans.add(level);\n    }\n    return ans;\n}',
      complexity: '时间 O(n) —— 每个节点入队出队各一次；空间 O(w)，w 是最大层宽（满二叉树时 O(n)）。',
      pitfall: '那个 size = q.size() 必须在内层循环之前取。如果写成 while(!q.isEmpty()) 且循环条件直接依赖队列长度，孩子入队会污染当前层的边界，层次就分不出来了。'
    },
    run: function (inp) {
      var arr = inp.a, f = [];
      // 由层序数组建树（null 表示空）
      var nodes = [], edges = [];
      arr.forEach(function (v, i) { if (v !== null) nodes.push({ id: i, v: v, x: 0, y: 0, s: '' }); });
      var idmap = {};
      nodes.forEach(function (nd) { idmap[nd.id] = nd; });
      arr.forEach(function (v, i) {
        if (v === null) return;
        var l = 2 * i + 1, r = 2 * i + 2;
        if (l < arr.length && arr[l] !== null) edges.push([i, l]);
        if (r < arr.length && arr[r] !== null) edges.push([i, r]);
      });
      // 布局：按层
      var depth = {};
      function dep(i) { return i === 0 ? 0 : dep(Math.floor((i - 1) / 2)) + 1; }
      nodes.forEach(function (nd) { nd.y = dep(nd.id); });
      var byLevel = {};
      nodes.forEach(function (nd) { (byLevel[nd.y] = byLevel[nd.y] || []).push(nd); });
      var maxd = Math.max.apply(null, nodes.map(function (n) { return n.y; }));
      byLevel[maxd].forEach(function (nd, k) { nd.x = k + 0.5; });
      for (var d = maxd - 1; d >= 0; d--) {
        (byLevel[d] || []).forEach(function (nd) {
          var kids = [];
          if (arr[2 * nd.id + 1] !== null && arr[2 * nd.id + 1] !== undefined) kids.push(idmap[2 * nd.id + 1]);
          if (arr[2 * nd.id + 2] !== null && arr[2 * nd.id + 2] !== undefined) kids.push(idmap[2 * nd.id + 2]);
          if (kids.length) nd.x = (kids[0].x + kids[kids.length - 1].x) / 2;
        });
      }
      var W = Math.max.apply(null, nodes.map(function (n) { return n.x; })) + 1.5;

      function snap() { return nodes.map(function (n) { return { id: n.id, v: n.v, x: n.x, y: n.y, s: n.s }; }); }
      function fr(note, q, level) {
        return {
          note: note, vars: { '队列长度': q.length, '当前层': level === undefined ? '-' : level },
          nodes: snap(), edges: edges, W: W, H: maxd + 1,
          queue: q.map(function (x) { return x.v; }), result: result.slice()
        };
      }
      var result = [];
      if (!arr.length || arr[0] === null) { f.push(fr('空树。', [], 0)); return f; }
      var q = [idmap[0]];
      f.push(fr('根节点 ' + arr[0] + ' 入队。', q, 0));
      var lv = 0;
      while (q.length) {
        var size = q.length;
        f.push(fr('当前层有 ' + size + ' 个节点。固定 size = ' + size + '，把它们一次性取完 —— 这就是一整层。', q, lv));
        var cur = [];
        for (var i = 0; i < size; i++) {
          var nd = q.shift();
          nd.s = 'active';
          cur.push(nd.v);
          f.push(fr('出队 ' + nd.v + '，访问它。', q, lv));
          var l = 2 * nd.id + 1, r = 2 * nd.id + 2;
          if (l < arr.length && arr[l] !== null) { idmap[l].s = 'queue'; q.push(idmap[l]); f.push(fr('左孩子 ' + arr[l] + ' 入队。', q, lv)); }
          if (r < arr.length && arr[r] !== null) { idmap[r].s = 'queue'; q.push(idmap[r]); f.push(fr('右孩子 ' + arr[r] + ' 入队。', q, lv)); }
          nd.s = 'done';
        }
        result.push(cur.slice());
        f.push(fr('第 ' + lv + ' 层 = [' + cur.join(', ') + ']，记录结果。' + (q.length ? '队列里剩下 ' + q.length + ' 个，正好是下一层的全部节点。' : '队列已空，遍历结束。'), q, lv));
        lv++;
      }
      f.push(fr('层序遍历完成：' + JSON.stringify(result), [], lv));
      return f;
    }
  });

  /* ============================================================
   * A020 课程表 —— 拓扑排序
   * ============================================================ */
  D.push({
    id: 'A020', title: '课程表（拓扑排序）', group: '图 · 拓扑 · 最短路',
    render: 'graph', star: 3,
    inputText: '4 | 1,0 | 2,0 | 3,1 | 3,2',
    parse: function (t) {
      var parts = t.trim().split(/\s*\|\s*/);
      var n = Number(parts[0]);
      var edges = parts.slice(1).map(function (p) { var q = p.split(','); return { a: Number(q[0]), b: Number(q[1]) }; });
      return { n: n, edges: edges };
    },
    desc: {
      intuition: '如果课程之间有依赖，能不能学完？等价于：这个有向图有没有环。办法是反复消灭"没有任何前置"的节点 —— 每拿掉一个，就把它指向的节点的入度减 1，于是可能又产生新的零入度节点。',
      invariant: 'indegree[v] 记录"还有几门前置课没学"。当它为 0 时，这门课就可以立刻学。每学完一门，它后继的入度减 1。若最终学过的课少于总数，说明剩下那些互相等待 —— 存在环。',
      steps: [
        '建图，统计每个节点的入度',
        '所有入度为 0 的节点入队',
        '出队一个 → 计数 +1，把它所有邻居的入度 −1',
        '邻居入度变 0 则入队',
        '计数 == n 则无环，否则有环'
      ],
      code: 'public boolean canFinish(int n, int[][] edges) {\n    List<Integer>[] g = new List[n];\n    int[] indeg = new int[n];\n    for (int i = 0; i < n; i++) g[i] = new ArrayList<>();\n    for (int[] e : edges) { g[e[1]].add(e[0]); indeg[e[0]]++; }  // e[1] → e[0]\n    Queue<Integer> q = new ArrayDeque<>();\n    for (int i = 0; i < n; i++) if (indeg[i] == 0) q.offer(i);\n    int cnt = 0;\n    while (!q.isEmpty()) {\n        int v = q.poll(); cnt++;\n        for (int w : g[v]) if (--indeg[w] == 0) q.offer(w);\n    }\n    return cnt == n;\n}',
      complexity: '时间 O(V + E)；空间 O(V + E)。',
      pitfall: '边的方向别搞反：prerequisites[i] = [a, b] 表示"学 a 之前要学 b"，所以边是 b → a。建反了会得到完全错误的结论。另外入度为 0 不一定唯一，必须用队列而不是单个变量。'
    },
    run: function (inp) {
      var n = inp.n, E = inp.edges, f = [];
      var g = {}, indeg = {};
      for (var i = 0; i < n; i++) { g[i] = []; indeg[i] = 0; }
      E.forEach(function (e) { g[e.b].push(e.a); indeg[e.a]++; });
      // 布局：环形
      var nodes = [];
      for (var v = 0; v < n; v++) {
        var ang = -Math.PI / 2 + v * 2 * Math.PI / n;
        nodes.push({ id: v, x: 50 + 36 * Math.cos(ang), y: 48 + 34 * Math.sin(ang), indeg: indeg[v], s: '' });
      }
      var edges = E.map(function (e) { return { a: e.b, b: e.a, s: '' }; });
      function snap() { return nodes.map(function (x) { return { id: x.id, x: x.x, y: x.y, indeg: indeg[x.id], s: x.s }; }); }
      function snapE() { return edges.map(function (e) { return { a: e.a, b: e.b, s: e.s }; }); }
      function fr(note, q, cnt) {
        return { note: note, vars: { 已学完: cnt, '队列': q.length ? '[' + q.join(', ') + ']' : '空' }, nodes: snap(), edges: snapE(), queue: q.slice(), cnt: cnt, n: n };
      }
      f.push(fr('建图并统计入度。入度 = 还有几门前置课没学。', [], 0));
      var q = [];
      for (var u = 0; u < n; u++) if (indeg[u] === 0) { q.push(u); nodes[u].s = 'ready'; }
      f.push(fr('入度为 0 的课程（' + (q.length ? q.join(', ') : '无') + '）可以直接学，入队。', q, 0));
      var cnt = 0;
      while (q.length) {
        var v = q.shift();
        nodes[v].s = 'done'; cnt++;
        f.push(fr('出队课程 ' + v + '，标记已学（第 ' + cnt + ' 门）。把它指向的课的入度 −1。', q, cnt));
        g[v].forEach(function (w) {
          edges.forEach(function (e) { if (e.a === v && e.b === w) e.s = 'relax'; });
          indeg[w]--;
          f.push(fr('课程 ' + w + ' 的入度减为 ' + indeg[w] + (indeg[w] === 0 ? ' → 变为 0，可以学了，入队。' : '，还要再等等。'), q, cnt));
          if (indeg[w] === 0) { nodes[w].s = 'ready'; q.push(w); }
        });
      }
      if (cnt === n) f.push(fr('全部 ' + n + ' 门课都能学完，无环，返回 true。', [], cnt));
      else {
        nodes.forEach(function (x) { if (x.s !== 'done') x.s = 'cycle'; });
        f.push(fr('只学完 ' + cnt + ' 门，还剩 ' + (n - cnt) + ' 门入度永远不为 0 —— 它们互相等待，存在环。返回 false。', [], cnt));
      }
      return f;
    }
  });

  /* ============================================================
   * A022 网络延迟时间 —— Dijkstra
   * ============================================================ */
  D.push({
    id: 'A022', title: '网络延迟时间（Dijkstra）', group: '图 · 拓扑 · 最短路',
    render: 'graph', star: 3,
    inputText: '1→2:1, 1→3:4, 2→3:2, 3→4:1, 2→4:6',
    parse: function (t) {
      var es = t.trim().split(/[,\s]+/).filter(Boolean).map(function (s) {
        var m = s.match(/(\d+)\s*(?:->|→)\s*(\d+)\s*:\s*(\d+)/);
        if (!m) throw new Error('边格式应为 起点->终点:权重，例如 1→2:3');
        return { a: Number(m[1]), b: Number(m[2]), w: Number(m[3]) };
      });
      return { edges: es };
    },
    desc: {
      intuition: '从源点开始，每次挑一个"当前距离最短的未确定节点"，宣布它的最短距离已经定型，然后用它去松弛邻居。贪心的正确性依赖于：所有边权非负，所以不可能存在一条绕道更近的路。',
      invariant: '一旦某节点被标记为"已确定"，dist[v] 就是最终答案，不会再变小。因为若有更短路径，那必然经过某个未确定节点 u，而 dist[u] ≥ dist[v] 且边权 ≥ 0，绕道只会更长。',
      steps: [
        'dist[源] = 0，其余 = ∞',
        '反复取出未确定节点中 dist 最小者 v，标记确定',
        '对 v 的每条出边 (v→w, cost)：若 dist[v] + cost < dist[w] 则更新（松弛）',
        '全部确定后，答案 = max(dist)'
      ],
      code: 'public int networkDelayTime(int[][] times, int n, int k) {\n    List<int[]>[] g = new List[n + 1];\n    for (int i = 1; i <= n; i++) g[i] = new ArrayList<>();\n    for (int[] t : times) g[t[0]].add(new int[]{ t[1], t[2] });\n    int[] dist = new int[n + 1];\n    Arrays.fill(dist, Integer.MAX_VALUE / 2);\n    dist[k] = 0;\n    PriorityQueue<int[]> pq = new PriorityQueue<>((a, b) -> a[1] - b[1]);\n    pq.offer(new int[]{ k, 0 });\n    while (!pq.isEmpty()) {\n        int[] cur = pq.poll();\n        int v = cur[0];\n        if (cur[1] > dist[v]) continue;        // 过期条目，跳过\n        for (int[] e : g[v]) {\n            int w = e[0], c = e[1];\n            if (dist[v] + c < dist[w]) {\n                dist[w] = dist[v] + c;\n                pq.offer(new int[]{ w, dist[w] });\n            }\n        }\n    }\n    int ans = 0;\n    for (int i = 1; i <= n; i++) {\n        if (dist[i] == Integer.MAX_VALUE / 2) return -1;   // 有节点不可达\n        ans = Math.max(ans, dist[i]);\n    }\n    return ans;\n}',
      complexity: '用优先队列 O((V+E) log V)；朴素实现 O(V²)。空间 O(V + E)。',
      pitfall: '两个必踩的坑：① 有负权边时 Dijkstra 失效，必须换 Bellman-Ford；② 优先队列里会有同一节点的多个过期条目，取出来时要用 if (cur[1] > dist[v]) continue 跳过，否则会重复松弛。'
    },
    run: function (inp) {
      var E = inp.edges, f = [];
      var ids = {};
      E.forEach(function (e) { ids[e.a] = 1; ids[e.b] = 1; });
      var list = Object.keys(ids).map(Number).sort(function (x, y) { return x - y; });
      var src = list[0];
      var g = {}; list.forEach(function (v) { g[v] = []; });
      E.forEach(function (e) { g[e.a].push({ to: e.b, w: e.w }); });
      var dist = {}; list.forEach(function (v) { dist[v] = Infinity; });
      dist[src] = 0;
      var pos = {};
      list.forEach(function (v, i) {
        var ang = -Math.PI / 2 + i * 2 * Math.PI / list.length;
        pos[v] = { x: 50 + 36 * Math.cos(ang), y: 48 + 34 * Math.sin(ang) };
      });
      var edges = E.map(function (e) { return { a: e.a, b: e.b, w: e.w, s: '' }; });
      function snap() { return list.map(function (v) { return { id: v, x: pos[v].x, y: pos[v].y, dist: dist[v], s: state[v] }; }); }
      var state = {}, done = {};
      list.forEach(function (v) { state[v] = ''; });
      state[src] = 'src';
      function fr(note, cur, hlE) {
        return {
          note: note, vars: { '已确定': Object.keys(done).join(',') || '无', '当前': cur === undefined ? '-' : cur },
          nodes: snap(), edges: edges.map(function (e, i) { return { a: e.a, b: e.b, w: e.w, s: (hlE && hlE.indexOf(i) >= 0) ? 'relax' : e.s }; }),
          dist: JSON.parse(JSON.stringify(dist)), src: src
        };
      }
      f.push(fr('源点 = ' + src + '，dist[' + src + '] = 0，其余为 ∞。', undefined, []));
      while (true) {
        var best = null;
        list.forEach(function (v) { if (!done[v] && dist[v] < Infinity && (best === null || dist[v] < dist[best])) best = v; });
        if (best === null) break;
        done[best] = 1; state[best] = 'done';
        f.push(fr('未确定节点中距离最小的是 ' + best + '（dist = ' + dist[best] + '）。标记确定 —— 这个距离不会再变小了。', best, []));
        var hl = [];
        g[best].forEach(function (e) {
          edges.forEach(function (x, i) { if (x.a === best && x.b === e.to && x.w === e.w) hl.push(i); });
          var nd = dist[best] + e.w;
          if (nd < dist[e.to]) {
            f.push(fr('松弛边 ' + best + '→' + e.to + '（权 ' + e.w + '）：' + dist[best] + ' + ' + e.w + ' = ' + nd + ' < 原来的 ' + (dist[e.to] === Infinity ? '∞' : dist[e.to]) + '，更新！', best, hl));
            dist[e.to] = nd; state[e.to] = 'relax';
          } else {
            f.push(fr('检查边 ' + best + '→' + e.to + '（权 ' + e.w + '）：' + dist[best] + ' + ' + e.w + ' = ' + nd + '，不比原来的 ' + (dist[e.to] === Infinity ? '∞' : dist[e.to]) + ' 更优，不更新。', best, hl));
          }
        });
      }
      var mx = 0, unreach = [];
      list.forEach(function (v) { if (dist[v] === Infinity) unreach.push(v); else mx = Math.max(mx, dist[v]); });
      if (unreach.length) f.push(fr('节点 ' + unreach.join(', ') + ' 不可达，返回 −1。', undefined, []));
      else f.push(fr('所有节点可达。答案 = max(dist) = ' + mx + '，即信号传遍全网所需的最短时间。', undefined, []));
      return f;
    }
  });

  /* ============================================================
   * A001 数组第 K 大 —— 快速选择
   * ============================================================ */
  D.push({
    id: 'A001', title: '数组第 K 大元素（快速选择）', group: '排序 · 堆 · 快速选择',
    render: 'bars', star: 3,
    inputText: '3,2,1,5,6,4 | 2',
    parse: function (t) {
      var p = t.split('|');
      return { a: p[0].split(/[,\s]+/).filter(Boolean).map(Number), k: Number(p[1]) };
    },
    desc: {
      intuition: '完整排序要 O(n log n)，但只问第 K 大 —— 没必要把整个数组排好。用快排的 partition：分完一趟后 pivot 的位置 p 就定了，只需要看 K 落在 p 的左边还是右边，然后**只递归那一侧**。',
      invariant: 'partition 后，pivot 位于下标 p，且左侧全 ≤ pivot、右侧全 ≥ pivot。因此第 K 大若 p == n−K 就是 pivot；若 p > n−K 只在左侧找；否则只在右侧找。每次丢弃一半。',
      steps: [
        '目标下标 = n − K（第 K 大对应升序的第 n−K 位）',
        'partition 得到 p',
        'p == n−K → 返回；p > n−K → 递归左半；否则递归右半',
        '平均每次丢弃一半，总代价 n + n/2 + n/4 + … = 2n'
      ],
      code: 'public int findKthLargest(int[] a, int k) {\n    int target = a.length - k;          // 升序数组中的下标\n    int l = 0, r = a.length - 1;\n    while (true) {\n        int p = partition(a, l, r);\n        if (p == target) return a[p];\n        else if (p > target) r = p - 1; // 只往左找\n        else l = p + 1;                 // 只往右找\n    }\n}',
      complexity: '平均 O(n)，最坏 O(n²)（同样依赖 pivot 质量）；空间 O(1)（迭代写法）。',
      pitfall: '第 K 大对应的下标是 n−K 而不是 K−1 —— 方向别搞反。另外平均 O(n) 的前提是随机 pivot，若固定取最右元素，在已排序数组上同样会退化。'
    },
    run: function (inp) {
      var a = inp.a.slice(), k = inp.k, f = [], target = a.length - k;
      function swap(i, j) { var t = a[i]; a[i] = a[j]; a[j] = t; }
      function fr(note, stt, ptr, lo, hi) {
        return {
          note: note, vars: { '目标下标 n−K': target, '第 K 大': k, '搜索范围': '[' + lo + ',' + hi + ']' },
          bars: a.map(function (v, i) { return { v: v, s: (i < lo || i > hi) ? 'out' : (stt && stt[i] ? stt[i] : 'in') }; }),
          ptr: ptr || {}
        };
      }
      var l = 0, r = a.length - 1;
      f.push(fr('第 ' + k + ' 大 → 升序数组中下标 n−K = ' + (a.length) + '−' + k + ' = ' + target + '。不需要全排序，只找这个位置。', {}, {}, l, r));
      var guard = 0;
      while (l <= r && guard++ < 30) {
        var pivot = a[r], i = l - 1;
        f.push(fr('在 [' + l + ', ' + r + '] 内 partition，取 pivot = a[' + r + '] = ' + pivot + '。', { [r]: 'pivot' }, {}, l, r));
        for (var j = l; j < r; j++) {
          if (a[j] <= pivot) {
            i++; swap(i, j);
            f.push(fr('a[' + j + ']=' + a[j] + ' ≤ ' + pivot + '，换到左侧，小于区右界 i = ' + i + '。', { [i]: 'ok', [r]: 'pivot' }, { j: j, i: i }, l, r));
          }
        }
        swap(i + 1, r);
        var p = i + 1;
        f.push(fr('partition 完成，pivot 落在下标 ' + p + '，值为 ' + a[p] + '。', { [p]: 'done' }, { p: p }, l, r));
        if (p === target) {
          f.push(fr('p = ' + p + ' == 目标下标 ' + target + '！答案就是 a[' + p + '] = ' + a[p] + '。注意此时数组并未完全排序 —— 这正是快速选择比全排序快的原因。', { [p]: 'ans' }, { p: p }, l, r));
          return f;
        } else if (p > target) {
          r = p - 1;
          f.push(fr(p + ' > ' + target + '，答案在左侧。右半边整个丢弃，范围缩为 [' + l + ', ' + r + ']。', {}, {}, l, r));
        } else {
          l = p + 1;
          f.push(fr(p + ' < ' + target + '，答案在右侧。左半边整个丢弃，范围缩为 [' + l + ', ' + r + ']。', {}, {}, l, r));
        }
      }
      return f;
    }
  });

})();
