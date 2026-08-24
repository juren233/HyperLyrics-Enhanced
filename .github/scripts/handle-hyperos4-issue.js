const HYPEROS4_FIELD_LABEL = '系统平台 / System Platform';
const HYPEROS4_FIELD_VALUE = 'HyperOS 4';
const HYPEROS4_LABEL = '还在搓 / In Progress';
const HYPEROS4_MESSAGE =
    '不好意思，HyperOS4 目前未经正式测试，期间遇到部分使用异常属于待修复的已知问题，预计8月底至9月初适配 HyperOS4，在此期间无法保证 HyperOS4 的使用体验，理解万岁🙏';
const HYPEROS4_MARKER = '<!-- hyperos4-compatibility-notice -->';

function extractIssueFormAnswer(body, fieldLabel) {
    const lines = String(body ?? '').replace(/\r\n?/g, '\n').split('\n');
    const heading = `### ${fieldLabel}`;
    const headingIndex = lines.findIndex((line) => line.trim() === heading);
    if (headingIndex === -1) {
        return '';
    }

    const answer = [];
    for (let index = headingIndex + 1; index < lines.length; index += 1) {
        const line = lines[index];
        if (/^###\s+/.test(line.trim())) {
            break;
        }
        answer.push(line);
    }
    return answer.join('\n').trim();
}

function isHyperOs4Issue(body) {
    return extractIssueFormAnswer(body, HYPEROS4_FIELD_LABEL) === HYPEROS4_FIELD_VALUE;
}

async function run({ github, context, core }) {
    const issue = context.payload.issue;
    if (!issue) {
        throw new Error('当前事件不包含 Issue 数据。');
    }

    if (!isHyperOs4Issue(issue.body)) {
        core.info(`Issue #${issue.number} 未选择 ${HYPEROS4_FIELD_VALUE}，跳过。`);
        return;
    }

    const { owner, repo } = context.repo;
    await github.rest.issues.addLabels({
        owner,
        repo,
        issue_number: issue.number,
        labels: [HYPEROS4_LABEL],
    });

    const comments = await github.paginate(github.rest.issues.listComments, {
        owner,
        repo,
        issue_number: issue.number,
        per_page: 100,
    });
    if (comments.some((comment) => comment.body?.includes(HYPEROS4_MARKER))) {
        core.info(`Issue #${issue.number} 已发布过 HyperOS 4 提示，仅确认标签。`);
        return;
    }

    await github.rest.issues.createComment({
        owner,
        repo,
        issue_number: issue.number,
        body: `${HYPEROS4_MESSAGE}\n\n${HYPEROS4_MARKER}`,
    });
    core.notice(`Issue #${issue.number} 已添加 HyperOS 4 标签和兼容性提示。`);
}

module.exports = run;
module.exports.testables = {
    HYPEROS4_FIELD_LABEL,
    HYPEROS4_FIELD_VALUE,
    HYPEROS4_LABEL,
    HYPEROS4_MARKER,
    HYPEROS4_MESSAGE,
    extractIssueFormAnswer,
    isHyperOs4Issue,
};
