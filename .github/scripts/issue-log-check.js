const INVALID_LABEL = '无效 / invalid';
const BUG_LABEL = 'bug';
const LOG_HEADING = 'LSPosed 日志或 HyperLyrics Enhanced 日志';
const NO_RESPONSE = '_No response_';
const MARKER = '<!-- issue-log-check:missing-logs -->';
const RELEASES_URL = 'https://github.com/juren233/HyperLyrics-Enhanced/releases';

const GUIDE_MESSAGE =
    '检测到当前 issue 的「LSPosed 日志或 HyperLyrics Enhanced 日志」一栏没有附带日志文件，无法定位问题，已自动标记为「无效」。\n' +
    '日志截图、录屏不能代替日志文件。请按以下步骤补充：\n\n' +
    `1. 安装 Debug 包（如果当前已经是 Debug 包，无需重复安装）：前往 [Releases](${RELEASES_URL}) 下载文件名含 \`debug\` 的 APK 安装，安装后点击主页右上角的刷新按钮，重启一次系统界面与所有音乐 App。\n` +
    '2. 将日志输出等级改为调试：HyperLyrics Enhanced 设置 →「调试信息」→「日志输出等级」→「调试日志」。\n' +
    '3. 复现问题：保持上述设置，重新操作一遍出问题的步骤。\n' +
    '4. 导出全部日志：复现后立即在同一「调试信息」页面点击「导出全部日志」。\n' +
    '5. 最后将完整的日志文件回传至当前 issue。\n\n' +
    '补充日志后将自动取消「无效」标记；**3 天内未补充，本 issue 将被锁定并关闭**。\n\n理解万岁🙏\n\n' +
    '---\n\n' +
    'The "LSPosed Logs or HyperLyrics Enhanced Logs" field of this issue has no log file attached, so the problem cannot be located and the issue has been labeled "invalid" automatically. ' +
    'Screenshots or screen recordings of logs are not a substitute for log files. Please follow these steps:\n\n' +
    `1. Install the Debug build (no need to reinstall if you are already on one): download the APK whose file name contains \`debug\` from [Releases](${RELEASES_URL}). After installing, tap the refresh button at the top right of the home page to restart System UI and all music apps once.\n` +
    '2. Set the log level to Debug: HyperLyrics Enhanced Settings → "Debug Info" → "Log Level" → "Debug".\n' +
    '3. Reproduce the problem: keep that setting and repeat the steps that trigger the issue.\n' +
    '4. Export all logs: right after reproducing, tap "Export All Logs" on the same "Debug Info" page.\n' +
    '5. Finally, send the complete log file back to this issue.\n\n' +
    'Once logs are added, the "invalid" label is removed automatically. **If no logs are added within 3 days, this issue will be locked and closed.**\n\nThanks for your understanding 🙏';

const CLOSE_MESSAGE =
    '当前 issue 被标记「无效」已满 3 天，仍未补充日志，将被锁定并关闭。如需继续反馈，请按上方指引准备好日志后开新的 issue。\n\n' +
    'This issue has been labeled "invalid" for 3 days without logs being added, so it will now be locked and closed. If you still need help, please open a new issue with logs prepared as described above.';
const CLOSE_MARKER = '<!-- issue-log-check:closed -->';

const IMAGE_OR_VIDEO_EXT = /\.(png|jpe?g|gif|webp|bmp|heic|svg|mp4|mov|webm|mkv|avi)(?:[?#].*)?$/i;
const URL_PATTERN = /https?:\/\/[^\s)>\]"'<]+/g;

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

/** 取出 Issue Form 渲染后「日志」一栏的正文；没有该栏目（旧模板等）返回 null。 */
function extractLogSection(body) {
    if (!body) {
        return null;
    }
    const lines = body.split(/\r?\n/);
    const start = lines.findIndex(
        (line) => /^###\s/.test(line) && line.includes(LOG_HEADING),
    );
    if (start === -1) {
        return null;
    }
    const content = [];
    for (let i = start + 1; i < lines.length; i += 1) {
        if (/^###\s/.test(lines[i])) {
            break;
        }
        content.push(lines[i]);
    }
    return content.join('\n').trim();
}

/** 该链接是否像日志：排除图片 / 视频（含 GitHub 的 user-attachments/assets 图片视频资源）。 */
function isLogLink(url) {
    if (/\/user-attachments\/assets\//i.test(url)) {
        return false;
    }
    return !IMAGE_OR_VIDEO_EXT.test(url);
}

/**
 * 文本里是否带了日志：文件附件或非图片视频的外部链接（网盘分享）。
 * 仅有截图 / 录屏、空内容、「_No response_」都不算。
 */
function hasLogEvidence(text) {
    const content = text?.trim() ?? '';
    if (!content || content === NO_RESPONSE) {
        return false;
    }
    return (content.match(URL_PATTERN) ?? []).some(isLogLink);
}

function bugReportHasLogs(body) {
    const section = extractLogSection(body);
    if (section === null) {
        return null;
    }
    return hasLogEvidence(section);
}

async function listComments(github, owner, repo, number) {
    return github.paginate(github.rest.issues.listComments, {
        owner,
        repo,
        issue_number: number,
        per_page: 100,
    });
}

async function removeInvalidLabel(github, owner, repo, number) {
    try {
        await github.rest.issues.removeLabel({
            owner,
            repo,
            issue_number: number,
            name: INVALID_LABEL,
        });
    } catch (error) {
        if (error.status !== 404) {
            throw error;
        }
    }
}

/** issues.opened：缺日志则回复指引并打「无效」标签。 */
async function checkOpened({ github, context, core }) {
    const { owner, repo } = context.repo;
    const issue = context.payload.issue;
    if (!hasLabel(issue, BUG_LABEL)) {
        core.info('不含 bug 标签，跳过。');
        return;
    }
    if (issue.user?.login?.toLowerCase() === owner.toLowerCase()) {
        core.info('由维护者本人发起，跳过。');
        return;
    }
    const hasLogs = bugReportHasLogs(issue.body);
    if (hasLogs === null) {
        core.info('Issue 正文没有日志栏目，无法判断，跳过。');
        return;
    }
    if (hasLogs) {
        core.info('已附带日志。');
        return;
    }
    await github.rest.issues.createComment({
        owner,
        repo,
        issue_number: issue.number,
        body: `${GUIDE_MESSAGE}\n\n${MARKER}`,
    });
    await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: issue.number,
        labels: [INVALID_LABEL],
    });
    core.notice(`Issue #${issue.number}：缺少日志，已回复指引并标记无效。`);
}

/**
 * issues.edited / issue_comment.created：发起人补充了日志，且标签是本检查打上的，
 * 则撤销「无效」标签。人工打的无效标签不会被自动撤销。
 */
async function checkSupplement({ github, context, core }) {
    const { owner, repo } = context.repo;
    const issue = context.payload.issue;
    const comment = context.payload.comment;
    if (issue.pull_request || issue.state !== 'open' || !hasLabel(issue, INVALID_LABEL)) {
        return;
    }
    if (comment && comment.user?.login !== issue.user?.login) {
        core.info('评论者不是发起人，跳过。');
        return;
    }

    const comments = await listComments(github, owner, repo, issue.number);
    if (!comments.some((entry) => entry.body?.includes(MARKER))) {
        core.info('无效标签不是由日志检查打上的，跳过。');
        return;
    }

    const supplemented = comment
        ? hasLogEvidence(comment.body)
        : bugReportHasLogs(issue.body) === true;
    if (!supplemented) {
        core.info('尚未检测到补充的日志。');
        return;
    }
    await removeInvalidLabel(github, owner, repo, issue.number);
    await github.rest.issues.createComment({
        owner,
        repo,
        issue_number: issue.number,
        body:
            '已收到日志，已取消「无效」标记，将继续排查。\n\n' +
            'Logs received. The "invalid" label has been removed and the issue will be looked into.',
    });
    core.notice(`Issue #${issue.number}：已补充日志，撤销无效标签。`);
}

/** 无效标签最近一次被打上的时间；取不到返回 null。 */
async function invalidLabeledAt(github, owner, repo, number) {
    const events = await github.paginate(github.rest.issues.listEvents, {
        owner,
        repo,
        issue_number: number,
        per_page: 100,
    });
    const times = events
        .filter((event) => event.event === 'labeled' && event.label?.name === INVALID_LABEL)
        .map((event) => Date.parse(event.created_at))
        .filter(Number.isFinite);
    return times.length > 0 ? Math.max(...times) : null;
}

/** 定时：带「无效」标签满 N 天的 Issue 留言、锁定并关闭。 */
async function closeInvalid({ github, context, core }) {
    const { owner, repo } = context.repo;
    const days = Number.parseInt(process.env.INVALID_TIMEOUT_DAYS ?? '3', 10);
    if (!Number.isSafeInteger(days) || days <= 0) {
        throw new Error(`INVALID_TIMEOUT_DAYS 必须是正整数，当前值为：${process.env.INVALID_TIMEOUT_DAYS}`);
    }
    const nowMs = Date.now();
    const failures = [];

    const items = await github.paginate(github.rest.issues.listForRepo, {
        owner,
        repo,
        state: 'open',
        labels: INVALID_LABEL,
        per_page: 100,
    });

    for (const item of items) {
        if (item.pull_request) {
            continue;
        }
        const label = `Issue #${item.number}`;
        try {
            const labeledAt = await invalidLabeledAt(github, owner, repo, item.number);
            if (labeledAt === null) {
                core.warning(`${label}：找不到打标签的事件，跳过。`);
                continue;
            }
            const dueAt = labeledAt + days * 24 * 60 * 60 * 1000;
            if (nowMs < dueAt) {
                core.info(`${label}：未满 ${days} 天，最早处理时间=${new Date(dueAt).toISOString()}。`);
                continue;
            }
            const comments = await listComments(github, owner, repo, item.number);
            if (!comments.some((entry) => entry.body?.includes(CLOSE_MARKER))) {
                await github.rest.issues.createComment({
                    owner,
                    repo,
                    issue_number: item.number,
                    body: `${CLOSE_MESSAGE}\n\n${CLOSE_MARKER}`,
                });
            }
            // 先锁定再关闭，锁定失败时下次任务仍会重试。
            await github.rest.issues.lock({
                owner,
                repo,
                issue_number: item.number,
                lock_reason: 'resolved',
            });
            await github.rest.issues.update({
                owner,
                repo,
                issue_number: item.number,
                state: 'closed',
                state_reason: 'not_planned',
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

/**
 * 手动补查存量：扫描所有开着的 bug issue，缺日志的补发指引并打「无效」标签。
 * 发起人在任意评论里已带日志的、已打过无效标签的、已发过指引的都跳过。
 * DRY_RUN=true 时只列出，不改动。
 */
async function backfillMissingLogs({ github, context, core }) {
    const { owner, repo } = context.repo;
    const dryRun = process.env.DRY_RUN !== 'false';
    const failures = [];
    const hits = [];

    const items = await github.paginate(github.rest.issues.listForRepo, {
        owner,
        repo,
        state: 'open',
        labels: BUG_LABEL,
        per_page: 100,
    });

    for (const item of items) {
        if (item.pull_request) {
            continue;
        }
        const label = `Issue #${item.number}`;
        try {
            if (item.user?.login?.toLowerCase() === owner.toLowerCase()) {
                core.info(`${label}：由维护者本人发起，跳过。`);
                continue;
            }
            if (hasLabel(item, INVALID_LABEL)) {
                core.info(`${label}：已有无效标签，跳过。`);
                continue;
            }
            const hasLogs = bugReportHasLogs(item.body);
            if (hasLogs === null) {
                core.info(`${label}：正文没有日志栏目，无法判断，跳过。`);
                continue;
            }
            if (hasLogs) {
                continue;
            }
            const comments = await listComments(github, owner, repo, item.number);
            if (comments.some((entry) => entry.body?.includes(MARKER))) {
                core.info(`${label}：已发过指引，跳过。`);
                continue;
            }
            const authorLogin = item.user?.login?.toLowerCase();
            if (comments.some((entry) =>
                entry.user?.login?.toLowerCase() === authorLogin && hasLogEvidence(entry.body))) {
                core.info(`${label}：发起人已在评论里提供日志，跳过。`);
                continue;
            }
            hits.push(item.number);
            if (dryRun) {
                core.notice(`${label}：缺日志（试运行，未改动）：${item.title}`);
                continue;
            }
            await github.rest.issues.createComment({
                owner,
                repo,
                issue_number: item.number,
                body: `${GUIDE_MESSAGE}\n\n${MARKER}`,
            });
            await github.rest.issues.addLabels({
                owner,
                repo,
                issue_number: item.number,
                labels: [INVALID_LABEL],
            });
            core.notice(`${label}：缺少日志，已回复指引并标记无效。`);
        } catch (error) {
            const message = error instanceof Error ? error.stack ?? error.message : String(error);
            failures.push(`${label}: ${message}`);
            core.error(`${label}：处理失败：${message}`);
        }
    }

    core.info(
        `${dryRun ? '试运行' : '已处理'}：共 ${hits.length} 个缺日志的 issue` +
        `${hits.length ? `（${hits.map((n) => `#${n}`).join('、')}）` : ''}。`,
    );
    if (failures.length > 0) {
        core.setFailed(`有 ${failures.length} 个项目处理失败，请查看日志。`);
    }
}

module.exports = { checkOpened, checkSupplement, closeInvalid, backfillMissingLogs };
module.exports.testables = {
    GUIDE_MESSAGE,
    bugReportHasLogs,
    extractLogSection,
    hasLogEvidence,
    hasLabel,
};
