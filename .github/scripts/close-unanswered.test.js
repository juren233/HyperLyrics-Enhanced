const assert = require('node:assert/strict');
const test = require('node:test');

const {
    DAY_MS,
    ISSUE_TIMEOUT_MESSAGE,
    PR_TIMEOUT_MESSAGE,
    evaluateInactivity,
    hasLabel,
    issueCommentActivities,
    latestCommitTime,
    pullRequestActivities,
} = require('./close-unanswered.js').testables;
const closeUnanswered = require('./close-unanswered.js');

const NOW = Date.parse('2026-08-11T00:00:00Z');

function daysAgo(days) {
    return new Date(NOW - days * DAY_MS).toISOString();
}

function comment(login, createdAt) {
    return { user: { login }, created_at: createdAt };
}

test('Issue 标签匹配兼容 GitHub 对象和字符串格式', () => {
    assert.equal(hasLabel({ labels: [{ name: 'bug' }] }, 'bug'), true);
    assert.equal(hasLabel({ labels: ['BUG'] }, 'bug'), true);
    assert.equal(hasLabel({ labels: [{ name: 'enhancement' }] }, 'bug'), false);
});

test('Issue 在维护者最新评论满七天且发起人未回复时关闭', () => {
    const activities = issueCommentActivities([
        comment('reporter', daysAgo(8)),
        comment('juren233', daysAgo(7)),
    ]);

    const result = evaluateInactivity({
        activities,
        authorLogin: 'reporter',
        maintainerLogin: 'juren233',
        timeoutDays: 7,
        nowMs: NOW,
    });

    assert.equal(result.eligible, true);
});

test('Issue 发起人在维护者最新评论后回复时不关闭', () => {
    const activities = issueCommentActivities([
        comment('juren233', daysAgo(8)),
        comment('reporter', daysAgo(7)),
    ]);

    const result = evaluateInactivity({
        activities,
        authorLogin: 'reporter',
        maintainerLogin: 'juren233',
        timeoutDays: 7,
        nowMs: NOW,
    });

    assert.equal(result.eligible, false);
    assert.equal(result.reason, '发起人已在维护者最新评论后回复');
});

test('维护者的新评论会重新开始计算 Issue 超时时间', () => {
    const activities = issueCommentActivities([
        comment('juren233', daysAgo(8)),
        comment('juren233', daysAgo(2)),
    ]);

    const result = evaluateInactivity({
        activities,
        authorLogin: 'reporter',
        maintainerLogin: 'juren233',
        timeoutDays: 7,
        nowMs: NOW,
    });

    assert.equal(result.eligible, false);
    assert.equal(result.dueAt, NOW + 5 * DAY_MS);
});

test('PR 普通评论、代码行评论和 Review 都参与回复判定', () => {
    const activities = pullRequestActivities(
        [comment('juren233', daysAgo(31))],
        [comment('contributor', daysAgo(30))],
        [{ user: { login: 'reviewer' }, submitted_at: daysAgo(29) }],
    );

    const result = evaluateInactivity({
        activities,
        authorLogin: 'contributor',
        maintainerLogin: 'juren233',
        timeoutDays: 30,
        nowMs: NOW,
        latestCommitMs: NOW - 31 * DAY_MS,
    });

    assert.equal(result.eligible, false);
    assert.equal(result.reason, '发起人已在维护者最新评论后回复');
});

test('PR 最新提交未满三十天时不关闭', () => {
    const activities = issueCommentActivities([
        comment('juren233', daysAgo(31)),
    ]);

    const result = evaluateInactivity({
        activities,
        authorLogin: 'contributor',
        maintainerLogin: 'juren233',
        timeoutDays: 30,
        nowMs: NOW,
        latestCommitMs: NOW - 29 * DAY_MS,
    });

    assert.equal(result.eligible, false);
    assert.equal(result.reason, 'PR 最新提交尚未超时');
    assert.equal(result.dueAt, NOW + DAY_MS);
});

test('PR 的维护者最新评论和最新提交都满三十天时关闭', () => {
    const activities = issueCommentActivities([
        comment('juren233', daysAgo(30)),
    ]);

    const result = evaluateInactivity({
        activities,
        authorLogin: 'contributor',
        maintainerLogin: 'juren233',
        timeoutDays: 30,
        nowMs: NOW,
        latestCommitMs: NOW - 30 * DAY_MS,
    });

    assert.equal(result.eligible, true);
});

test('PR 提交时间同时考虑 author date 与 committer date 中较新者', () => {
    const result = latestCommitTime([
        {
            commit: {
                author: { date: daysAgo(40) },
                committer: { date: daysAgo(20) },
            },
        },
    ]);

    assert.equal(result, NOW - 20 * DAY_MS);
});

test('自动回复文案保持为约定文本', () => {
    assert.equal(
        ISSUE_TIMEOUT_MESSAGE,
        '当前 issue 已超时一周尚未回复，将默认当前 issue 已解决，如后续仍有问题，请开新的 issue 进行反馈，当前 issue 将被关闭！',
    );
    assert.equal(
        PR_TIMEOUT_MESSAGE,
        '当前 PR 已超时三十天尚未回复且尚无新提交，将默认当前 PR 已不再维护，如后续仍有需要，请开新的 PR 进行申请，当前 PR 将被关闭！',
    );
});

test('完整流程只清理普通 bug Issue，跳过还在搓 Issue，并继续处理符合条件的 PR', async () => {
    const oldTime = new Date(Date.now() - 40 * DAY_MS).toISOString();
    const calls = [];
    const endpoints = {
        listForRepo() {},
        listComments() {},
        listReviewComments() {},
        listReviews() {},
        listCommits() {},
    };
    const data = new Map([
        [endpoints.listForRepo, [
            { number: 1, user: { login: 'reporter' }, labels: [{ name: 'bug' }] },
            { number: 2, user: { login: 'contributor' }, pull_request: {} },
            { number: 3, user: { login: 'reporter' }, labels: [{ name: 'enhancement' }] },
            {
                number: 4,
                user: { login: 'reporter' },
                labels: [{ name: 'bug' }, { name: '还在搓 / In Progress' }],
            },
        ]],
        [endpoints.listReviewComments, []],
        [endpoints.listReviews, []],
        [endpoints.listCommits, [{
            commit: {
                author: { date: oldTime },
                committer: { date: oldTime },
            },
        }]],
    ]);
    const github = {
        paginate: async (endpoint, parameters) => {
            if (endpoint === endpoints.listComments) {
                return [comment('juren233', oldTime)];
            }
            return data.get(endpoint) ?? [];
        },
        rest: {
            issues: {
                listForRepo: endpoints.listForRepo,
                listComments: endpoints.listComments,
                createComment: async (parameters) => calls.push(['comment', parameters]),
                lock: async (parameters) => calls.push(['lock', parameters]),
                update: async (parameters) => calls.push(['close-issue', parameters]),
            },
            pulls: {
                listReviewComments: endpoints.listReviewComments,
                listReviews: endpoints.listReviews,
                listCommits: endpoints.listCommits,
                update: async (parameters) => calls.push(['close-pr', parameters]),
            },
        },
    };
    const failures = [];
    const core = {
        info() {},
        notice() {},
        warning() {},
        error(message) { failures.push(message); },
        setFailed(message) { failures.push(message); },
    };

    await closeUnanswered({
        github,
        context: { repo: { owner: 'juren233', repo: 'HyperLyrics-Enhanced' } },
        core,
    });

    assert.deepEqual(failures, []);
    assert.deepEqual(calls.map(([name]) => name), [
        'comment',
        'lock',
        'close-issue',
        'comment',
        'lock',
        'close-pr',
    ]);
    assert.equal(calls[0][1].body, `${ISSUE_TIMEOUT_MESSAGE}\n\n<!-- close-unanswered:issue -->`);
    assert.equal(calls[2][1].state_reason, 'completed');
    assert.equal(calls[3][1].body, `${PR_TIMEOUT_MESSAGE}\n\n<!-- close-unanswered:pr -->`);
    assert.equal(calls[5][1].state, 'closed');
});
