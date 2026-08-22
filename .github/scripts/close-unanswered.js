const DAY_MS = 24 * 60 * 60 * 1000;

const ISSUE_TIMEOUT_MESSAGE =
    '当前 issue 已超时一周尚未回复，将默认当前 issue 已解决，如后续仍有问题，请开新的 issue 进行反馈，当前 issue 将被关闭！';
const PR_TIMEOUT_MESSAGE =
    '当前 PR 已超时三十天尚未回复且尚无新提交，将默认当前 PR 已不再维护，如后续仍有需要，请开新的 PR 进行申请，当前 PR 将被关闭！';
const ISSUE_MARKER = '<!-- close-unanswered:issue -->';
const PR_MARKER = '<!-- close-unanswered:pr -->';

function normalizedLogin(login) {
    return login?.trim().toLowerCase() ?? '';
}

function normalizedLabel(label) {
    if (typeof label === 'string') {
        return label.trim().toLowerCase();
    }
    return label?.name?.trim().toLowerCase() ?? '';
}

function hasLabel(item, label) {
    const expected = label.trim().toLowerCase();
    return item.labels?.some((entry) => normalizedLabel(entry) === expected) ?? false;
}

function parsePositiveDays(name, value) {
    const days = Number.parseInt(value, 10);
    if (!Number.isSafeInteger(days) || days <= 0) {
        throw new Error(`${name} 必须是正整数，当前值为：${value}`);
    }
    return days;
}

function timestamp(value) {
    const milliseconds = Date.parse(value);
    if (!Number.isFinite(milliseconds)) {
        throw new Error(`无法解析时间：${value}`);
    }
    return milliseconds;
}

function activity(login, at, kind) {
    if (!login || !at) {
        return null;
    }
    return {
        login: normalizedLogin(login),
        at,
        atMs: timestamp(at),
        kind,
    };
}

function issueCommentActivities(comments) {
    return comments
        .map((comment) => activity(comment.user?.login, comment.created_at, '普通评论'))
        .filter(Boolean);
}

function pullRequestActivities(issueComments, reviewComments, reviews) {
    return [
        ...issueCommentActivities(issueComments),
        ...reviewComments
            .map((comment) => activity(comment.user?.login, comment.created_at, '代码行评论'))
            .filter(Boolean),
        ...reviews
            .map((review) => activity(review.user?.login, review.submitted_at, 'Review'))
            .filter(Boolean),
    ];
}

function latestActivityFor(activities, login) {
    const normalized = normalizedLogin(login);
    return activities
        .filter((entry) => entry.login === normalized)
        .reduce((latest, entry) => {
            if (!latest || entry.atMs > latest.atMs) {
                return entry;
            }
            return latest;
        }, null);
}

function latestCommitTime(commits) {
    const times = commits.flatMap((commit) => [
        commit.commit?.author?.date,
        commit.commit?.committer?.date,
    ]).filter(Boolean);

    if (times.length === 0) {
        return null;
    }
    return Math.max(...times.map(timestamp));
}

function evaluateInactivity({
    activities,
    authorLogin,
    maintainerLogin,
    timeoutDays,
    nowMs,
    latestCommitMs = undefined,
}) {
    const latestMaintainerActivity = latestActivityFor(activities, maintainerLogin);
    if (!latestMaintainerActivity) {
        return { eligible: false, reason: '没有维护者评论' };
    }

    const latestAuthorActivity = latestActivityFor(activities, authorLogin);
    if (latestAuthorActivity && latestAuthorActivity.atMs >= latestMaintainerActivity.atMs) {
        return {
            eligible: false,
            reason: '发起人已在维护者最新评论后回复',
            latestMaintainerActivity,
            latestAuthorActivity,
        };
    }

    const maintainerDueAt = latestMaintainerActivity.atMs + timeoutDays * DAY_MS;
    if (nowMs < maintainerDueAt) {
        return {
            eligible: false,
            reason: '维护者最新评论尚未超时',
            latestMaintainerActivity,
            dueAt: maintainerDueAt,
        };
    }

    if (latestCommitMs === null) {
        return {
            eligible: false,
            reason: '无法确认 PR 最新提交时间',
            latestMaintainerActivity,
        };
    }

    if (latestCommitMs !== undefined) {
        const commitDueAt = latestCommitMs + timeoutDays * DAY_MS;
        if (nowMs < commitDueAt) {
            return {
                eligible: false,
                reason: 'PR 最新提交尚未超时',
                latestMaintainerActivity,
                latestCommitMs,
                dueAt: commitDueAt,
            };
        }
    }

    return {
        eligible: true,
        latestMaintainerActivity,
        latestAuthorActivity,
        latestCommitMs,
        dueAt: Math.max(maintainerDueAt, latestCommitMs ?? maintainerDueAt),
    };
}

function hasMarker(comments, marker) {
    return comments.some((comment) => comment.body?.includes(marker));
}

function formatTime(milliseconds) {
    return new Date(milliseconds).toISOString();
}

async function closeAndLock({
    github,
    owner,
    repo,
    number,
    isPullRequest,
    comments,
}) {
    const marker = isPullRequest ? PR_MARKER : ISSUE_MARKER;
    const message = isPullRequest ? PR_TIMEOUT_MESSAGE : ISSUE_TIMEOUT_MESSAGE;

    if (!hasMarker(comments, marker)) {
        await github.rest.issues.createComment({
            owner,
            repo,
            issue_number: number,
            body: `${message}\n\n${marker}`,
        });
    }

    // 先锁定再关闭，使关闭成功但锁定偶发失败时仍能由下次任务重试。
    await github.rest.issues.lock({
        owner,
        repo,
        issue_number: number,
        lock_reason: 'resolved',
    });

    if (isPullRequest) {
        await github.rest.pulls.update({
            owner,
            repo,
            pull_number: number,
            state: 'closed',
        });
    } else {
        await github.rest.issues.update({
            owner,
            repo,
            issue_number: number,
            state: 'closed',
            state_reason: 'completed',
        });
    }
}

async function run({ github, context, core }) {
    const { owner, repo } = context.repo;
    const maintainerLogin = process.env.MAINTAINER_LOGIN?.trim() || owner;
    const issueLabel = process.env.ISSUE_LABEL?.trim() || 'bug';
    const issueExcludedLabel =
        process.env.ISSUE_EXCLUDED_LABEL?.trim() || '还在搓 / In Progress';
    const issueTimeoutDays = parsePositiveDays(
        'ISSUE_TIMEOUT_DAYS',
        process.env.ISSUE_TIMEOUT_DAYS ?? '7',
    );
    const prTimeoutDays = parsePositiveDays(
        'PR_TIMEOUT_DAYS',
        process.env.PR_TIMEOUT_DAYS ?? '30',
    );
    const nowMs = Date.now();
    const failures = [];

    const openItems = await github.paginate(github.rest.issues.listForRepo, {
        owner,
        repo,
        state: 'open',
        sort: 'updated',
        direction: 'asc',
        per_page: 100,
    });

    core.info(
        `检查 ${openItems.length} 个开放项目；维护者=${maintainerLogin}，` +
        `Issue=${issueTimeoutDays} 天（仅限标签=${issueLabel}，` +
        `排除标签=${issueExcludedLabel}），PR=${prTimeoutDays} 天。`,
    );

    for (const item of openItems) {
        const number = item.number;
        const authorLogin = item.user?.login;
        const isPullRequest = Boolean(item.pull_request);
        const label = `${isPullRequest ? 'PR' : 'Issue'} #${number}`;

        if (!isPullRequest && !hasLabel(item, issueLabel)) {
            core.info(`${label}：不含 ${issueLabel} 标签，跳过。`);
            continue;
        }
        if (!isPullRequest && hasLabel(item, issueExcludedLabel)) {
            core.info(`${label}：含 ${issueExcludedLabel} 标签，跳过。`);
            continue;
        }

        if (!authorLogin) {
            core.warning(`${label}：无法确认发起人，跳过。`);
            continue;
        }
        if (normalizedLogin(authorLogin) === normalizedLogin(maintainerLogin)) {
            core.info(`${label}：由维护者本人发起，跳过。`);
            continue;
        }

        try {
            const issueCommentsPromise = github.paginate(github.rest.issues.listComments, {
                owner,
                repo,
                issue_number: number,
                per_page: 100,
            });

            let issueComments;
            let activities;
            let latestCommitMs;

            if (isPullRequest) {
                const [comments, reviewComments, reviews, commits] = await Promise.all([
                    issueCommentsPromise,
                    github.paginate(github.rest.pulls.listReviewComments, {
                        owner,
                        repo,
                        pull_number: number,
                        per_page: 100,
                    }),
                    github.paginate(github.rest.pulls.listReviews, {
                        owner,
                        repo,
                        pull_number: number,
                        per_page: 100,
                    }),
                    github.paginate(github.rest.pulls.listCommits, {
                        owner,
                        repo,
                        pull_number: number,
                        per_page: 100,
                    }),
                ]);
                issueComments = comments;
                activities = pullRequestActivities(comments, reviewComments, reviews);
                latestCommitMs = latestCommitTime(commits);
            } else {
                issueComments = await issueCommentsPromise;
                activities = issueCommentActivities(issueComments);
            }

            const result = evaluateInactivity({
                activities,
                authorLogin,
                maintainerLogin,
                timeoutDays: isPullRequest ? prTimeoutDays : issueTimeoutDays,
                nowMs,
                latestCommitMs,
            });

            if (!result.eligible) {
                const due = result.dueAt ? `，最早处理时间=${formatTime(result.dueAt)}` : '';
                core.info(`${label}：${result.reason}${due}，跳过。`);
                continue;
            }

            core.info(
                `${label}：已满足自动关闭条件；维护者最新评论=` +
                `${result.latestMaintainerActivity.at} (${result.latestMaintainerActivity.kind})` +
                (isPullRequest ? `，PR 最新提交=${formatTime(result.latestCommitMs)}` : '') +
                '。',
            );

            await closeAndLock({
                github,
                owner,
                repo,
                number,
                isPullRequest,
                comments: issueComments,
            });
            core.notice(`${label}：已留言、锁定并关闭。`);
        } catch (error) {
            const message = error instanceof Error ? error.stack ?? error.message : String(error);
            failures.push(`${label}: ${message}`);
            core.error(`${label}：处理失败：${message}`);
        }
    }

    if (failures.length > 0) {
        core.setFailed(`有 ${failures.length} 个项目处理失败，请查看日志。`);
    }
}

module.exports = run;
module.exports.testables = {
    DAY_MS,
    ISSUE_TIMEOUT_MESSAGE,
    PR_TIMEOUT_MESSAGE,
    evaluateInactivity,
    hasLabel,
    issueCommentActivities,
    latestCommitTime,
    pullRequestActivities,
};
