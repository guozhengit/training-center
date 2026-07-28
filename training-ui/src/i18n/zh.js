export default {
  app: {
    title: '郭永争专属面试训练面板',
    eyebrow: 'Interview Training Center',
    heroText: '把 120 道机试题、80 道口述题、项目答辩材料和 Starter 验证报告放到一个可视化入口里。',
    loading: '正在加载训练数据...',
    statusChecking: '检测中',
    matrixLoaded: '矩阵报告已加载',
    matrixWaiting: '等待矩阵报告'
  },
  dashboard: {
    coding: '机试题',
    oral: '口述题',
    project: '项目答辩',
    totalQuestions: '总题数'
  },
  session: {
    create: '创建训练',
    creating: '创建中...',
    mode: '训练',
    sessionId: 'Session',
    coding: '机试题',
    oral: '口述题',
    project: '项目答辩',
    mixed: '混合'
  },
  attempt: {
    judge: '运行自动判题',
    judging: '判题中...',
    judgeResult: '判题结果',
    verdict: 'Verdict',
    tests: 'Tests',
    duration: '耗时',
    sandbox: '沙箱',
    sandboxHint: '自动判题会先创建隔离沙箱；如需修改代码，可在返回的 sandboxPath 中编辑后再次运行判题。',
    result: '结果',
    passed: '通过 / 表达达标',
    failed: '失败 / 需要复习',
    durationSeconds: '用时（秒）',
    answerUnlocked: '已看答案',
    notes: '复盘备注',
    notesPlaceholder: '哪里卡住、下次怎么说得更好',
    improvedAnswer: '优化后的答案',
    improvedPlaceholder: '沉淀一版 1-3 分钟口述稿或关键思路',
    submit: '记录本题结果',
    finished: '完成'
  },
  recorder: {
    startRecording: '开始录音',
    stopRecording: '停止录音',
    startTimer: '开始计时',
    stopTimer: '停止计时',
    reset: '重置',
    timing: '计时中',
    micDenied: '麦克风权限被拒绝',
    notSupported: '当前浏览器不支持录音',
    startFailed: '录音启动失败'
  },
  hint: {
    show: '查看提示',
    more: '更多提示',
    collapse: '收起',
    level0: '仅签名',
    level1: '签名 + 提示',
    level2: '完整源码'
  },
  report: {
    title: '训练报告',
    distribution: '通过 / 失败分布',
    trend: '最近用时趋势',
    totalPractice: '总练习',
    passRate: '通过率',
    dueReview: '待复习',
    avgDuration: '平均用时',
    avgOralScore: '口述均分',
    seconds: '秒'
  },
  history: {
    title: '训练历史',
    exportMd: '导出 MD',
    exportCsv: '导出 CSV',
    retrain: '失败题重练',
    detail: '详情',
    load: '加载',
    copyPath: '复制路径',
    copyCd: '复制 cd',
    openSandbox: '打开沙箱'
  },
  matrix: {
    title: 'Starter 验证矩阵',
    contract: '契约测试',
    expectedFailures: 'Starter 预期失败',
    referencePasses: '参考答案通过',
    ready: '矩阵验证通过'
  },
  questions: {
    title: '题库浏览',
    all: '全部',
    load: '加载题目'
  },
  score: {
    correctness: '正确性',
    structure: '结构性',
    projectEvidence: '项目证据',
    tradeoff: '权衡',
    factRestraint: '事实克制',
    total: '总分'
  }
}
