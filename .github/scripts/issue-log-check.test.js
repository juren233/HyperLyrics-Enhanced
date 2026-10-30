const assert = require('node:assert/strict');
const test = require('node:test');

const {
    GUIDE_MESSAGE,
    bugReportHasLogs,
    extractLogSection,
    hasLabel,
    hasLogEvidence,
} = require('./issue-log-check.js').testables;

function form(logs, after = '### 截图 / 录屏 / 网盘链接 / Screenshots\n\n_No response_') {
    return [
        '### 预期效果 / Expected Behavior',
        '',
        '正常',
        '',
        '### LSPosed 日志或 HyperLyrics Enhanced 日志 / LSPosed Logs or HyperLyrics Enhanced Logs',
        '',
        logs,
        '',
        after,
    ].join('\n');
}

const FILE = '[hyperlyricsenhanced_all_logs_20261026_1750.txt](https://github.com/user-attachments/files/33958927/hyperlyricsenhanced_all_logs_20261026_1750.txt)';
const IMAGE = '<img width="1200" alt="Image" src="https://github.com/user-attachments/assets/5a40e8f7-5cfb-4807-904f-527d280e077f" />';

test('只截取日志栏目，不受其他栏目内容影响', () => {
    const body = form('_No response_', `### 截图\n\n${FILE}`);
    assert.equal(extractLogSection(body), '_No response_');
    assert.equal(bugReportHasLogs(body), false);
});

test('文件附件、zip 和网盘链接都算日志', () => {
    assert.equal(bugReportHasLogs(form(FILE)), true);
    assert.equal(
        bugReportHasLogs(form('https://github.com/user-attachments/files/33500315/LSPosed_20261015_231237.zip')),
        true,
    );
    assert.equal(bugReportHasLogs(form('https://pan.baidu.com/s/1abcdef 提取码 1234')), true);
});

test('粘贴的代码块不算日志', () => {
    assert.equal(bugReportHasLogs(form('```\n10-30 12:00:00 E/HLE: boom\n```')), false);
});

test('空内容、_No response_、纯文字不算日志', () => {
    assert.equal(bugReportHasLogs(form('_No response_')), false);
    assert.equal(bugReportHasLogs(form('')), false);
    assert.equal(bugReportHasLogs(form('日志太长了没法发')), false);
});

test('日志截图和录屏不算日志', () => {
    assert.equal(bugReportHasLogs(form(IMAGE)), false);
    assert.equal(
        bugReportHasLogs(form('![x](https://github.com/user-attachments/assets/3b9ab9ae-3305-4275-8107-b1116b5ee091)')),
        false,
    );
    assert.equal(bugReportHasLogs(form('https://example.com/screen.PNG?raw=1')), false);
    assert.equal(bugReportHasLogs(form('https://example.com/record.mp4')), false);
});

test('截图加日志文件时算已附带日志', () => {
    assert.equal(bugReportHasLogs(form(`${IMAGE}\n${FILE}`)), true);
});

test('没有日志栏目的正文无法判断，返回 null', () => {
    assert.equal(bugReportHasLogs('### 问题详细描述\n\n随便写写'), null);
    assert.equal(bugReportHasLogs(''), null);
    assert.equal(bugReportHasLogs(undefined), null);
});

test('评论文本同样按日志证据判断', () => {
    assert.equal(hasLogEvidence(FILE), true);
    assert.equal(hasLogEvidence('好的我晚点发'), false);
    assert.equal(hasLogEvidence(undefined), false);
});

test('标签匹配兼容对象和字符串', () => {
    assert.equal(hasLabel({ labels: [{ name: '无效 / invalid' }] }, '无效 / invalid'), true);
    assert.equal(hasLabel({ labels: ['BUG'] }, 'bug'), true);
    assert.equal(hasLabel({ labels: [] }, 'bug'), false);
});

test('指引包含中英文与全部操作步骤', () => {
    for (const text of ['Debug', '调试日志', '导出全部日志', 'Releases', 'Export All Logs', '3 天', '3 days']) {
        assert.ok(GUIDE_MESSAGE.includes(text), `缺少：${text}`);
    }
});
