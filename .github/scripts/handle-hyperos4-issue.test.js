const assert = require('node:assert/strict');
const test = require('node:test');

const handleHyperOs4Issue = require('./handle-hyperos4-issue.js');
const {
    HYPEROS4_LABEL,
    HYPEROS4_MARKER,
    HYPEROS4_MESSAGE,
    extractIssueFormAnswer,
    isHyperOs4Issue,
} = handleHyperOs4Issue.testables;

const HYPEROS4_BODY = `### 系统平台 / System Platform

HyperOS 4

### 系统具体版本号 / Specific System Version

OS4.0.0.1`;

test('HyperOS 4 标签和自动回复文案保持为约定文本', () => {
    assert.equal(HYPEROS4_LABEL, '还在搓 / In Progress');
    assert.equal(
        HYPEROS4_MESSAGE,
        '不好意思，HyperOS4 目前未经正式测试，期间如遇到无法正常使用属于已知问题，预计8月底至9月初适配 HyperOS4，在此期间无法保证 HyperOS4 的使用体验，理解万岁🙏',
    );
});

test('从 Issue Form 正确读取系统平台', () => {
    assert.equal(
        extractIssueFormAnswer(HYPEROS4_BODY, '系统平台 / System Platform'),
        'HyperOS 4',
    );
    assert.equal(isHyperOs4Issue(HYPEROS4_BODY), true);
    assert.equal(isHyperOs4Issue(HYPEROS4_BODY.replace('HyperOS 4', 'HyperOS 3')), false);
});

test('正文其它位置提到 HyperOS 4 不会误触发', () => {
    const body = `### 系统平台 / System Platform

HyperOS 3

### 问题详细描述 / Detailed Description of the Issue

与 HyperOS 4 的表现不同。`;
    assert.equal(isHyperOs4Issue(body), false);
});

function createHarness(body, comments = []) {
    const calls = [];
    const endpoints = { listComments() {} };
    const github = {
        paginate: async (endpoint) => {
            assert.equal(endpoint, endpoints.listComments);
            return comments;
        },
        rest: {
            issues: {
                addLabels: async (parameters) => calls.push(['label', parameters]),
                listComments: endpoints.listComments,
                createComment: async (parameters) => calls.push(['comment', parameters]),
            },
        },
    };
    const core = { info() {}, notice() {} };
    const context = {
        repo: { owner: 'juren233', repo: 'HyperLyrics-Enhanced' },
        payload: { issue: { number: 42, body } },
    };
    return { calls, context, core, github };
}

test('HyperOS 4 Issue 会添加标签并回复固定话术', async () => {
    const harness = createHarness(HYPEROS4_BODY);
    await handleHyperOs4Issue(harness);

    assert.deepEqual(harness.calls.map(([name]) => name), ['label', 'comment']);
    assert.deepEqual(harness.calls[0][1].labels, [HYPEROS4_LABEL]);
    assert.equal(
        harness.calls[1][1].body,
        `${HYPEROS4_MESSAGE}\n\n${HYPEROS4_MARKER}`,
    );
});

test('已有提示标记时只确认标签，不重复回复', async () => {
    const harness = createHarness(HYPEROS4_BODY, [{ body: HYPEROS4_MARKER }]);
    await handleHyperOs4Issue(harness);

    assert.deepEqual(harness.calls.map(([name]) => name), ['label']);
});

test('非 HyperOS 4 Issue 不执行写操作', async () => {
    const harness = createHarness(HYPEROS4_BODY.replace('HyperOS 4', 'HyperOS 3'));
    await handleHyperOs4Issue(harness);

    assert.deepEqual(harness.calls, []);
});
