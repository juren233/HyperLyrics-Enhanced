/**
 * 官包检测:解析 issue 日志里的「手机型号 + 系统版本号」,比对 rom-codes.json 快照。
 *
 * 判定规则(与维护者约定,2026-11-14):
 * - official:版本尾段设备码归属该机型 → 不处理。
 * - ported:  尾段设备码归属另一台已知机型(唯一匹配) → 打「无效」标签 + 自动回复;
 *            3 天定时任务会锁定并关闭,发起人补充 APK / 版本号说明后自动摘标签转人工。
 * - unknown: 查无 / 机型对不上快照 / 无尾段(内测、灰度窗口等) → 不处理。
 * 只证伪不证实:伪装完整的移植包与官包字符串无异,任何「查无」都不定罪。
 */
const FS = require('fs');
const PATH = require('path');

const INVALID_LABEL = '无效 / invalid';
const ROM_MARKER = '<!-- issue-rom-check:ported -->';
const SNAPSHOT_PATH = PATH.join(__dirname, '..', 'data', 'rom-codes.json');
/** 只解析日志头部:环境信息区在前 64KB 内,大文件避免全量读。 */
const LOG_TEXT_LIMIT = 64 * 1024;

/** 应用内导出日志的环境字段(中英两套文案,见 strings.xml format_export_device_model / format_export_system_version)。 */
const DEVICE_MODEL_LINE = /^(?:手机型号|Device Model):\s*(.+)$/m;
const SYSTEM_VERSION_LINE = /^(?:系统版本号|System Version):\s*(.+)$/m;

/** 版本号尾段判定(与 rom-data-sync.js 保持一致):点分段、大写字母数字、以已知后缀族结尾。 */
const TAIL_SEGMENT = /^[A-Z][A-Z0-9]{1,9}$/;
const SUFFIX_FAMILY = /(XM|DM|DC|CR|TC)$/;
/** 发起人补充材料识别:.apk 附件链接,或正文写出 OS/MIUI 版本号。 */
const APK_LINK = /https?:\/\/\S+\.apk(?:[?#]\S*)?$/im;
const VERSION_TEXT = /\b(?:OS|V)\d+\.\d+(?:\.\d+)*\.\d+/i;

function extractTailSegments(version) {
    return String(version || '')
        .split('.')
        .filter((segment) => TAIL_SEGMENT.test(segment) && SUFFIX_FAMILY.test(segment));
}

/** 机型名归一化:与 rom-data-sync.js 保持一致(保留 CJK、字母数字与 +)。 */
function normalizeName(name) {
    return String(name || '')
        .toLowerCase()
        .replace(/[^\p{Script=Han}\p{Letter}\p{Number}+]+/gu, '');
}

/** 日志正文里的环境信息;缺任一字段返回 null。 */
function extractEnvironment(logText) {
    const model = logText?.match(DEVICE_MODEL_LINE)?.[1]?.trim();
    const version = logText?.match(SYSTEM_VERSION_LINE)?.[1]?.trim();
    if (!model || !version) {
        return null;
    }
    return { deviceModel: model, systemVersion: version };
}

/**
 * 核心判定。
 * @returns {{verdict: 'official'|'ported'|'unknown', reason: string, evidence?: object}}
 */
function judgeDeviceRom(deviceModel, systemVersion, snapshot) {
    const codes = snapshot.names[normalizeName(deviceModel)];
    if (!codes || codes.length === 0) {
        return { verdict: 'unknown', reason: `机型「${deviceModel}」不在快照中` };
    }
    const tails = extractTailSegments(systemVersion);
    if (tails.length === 0) {
        return { verdict: 'unknown', reason: `版本号「${systemVersion}」无官方尾段(内测或非标准格式)` };
    }
    let owners;
    for (const tail of tails) {
        const tailOwners = snapshot.tailOwners[tail];
        if (!tailOwners) {
            return { verdict: 'unknown', reason: `尾段 ${tail} 未收录(可能为灰度/新批次)` };
        }
        if (!owners) {
            owners = tailOwners;
        } else if (owners.join() !== tailOwners.join()) {
            return { verdict: 'unknown', reason: `版本号含多个不一致尾段:${tails.join(' / ')}` };
        }
    }
    if (owners.some((owner) => codes.includes(owner))) {
        return { verdict: 'official', reason: `机型「${deviceModel}」与尾段 ${tails.join(' / ')} 吻合` };
    }
    if (codes.length === 1 && owners.length === 1) {
        const ownerCode = owners[0];
        const ownerNames = snapshot.codeNames[ownerCode] ?? [];
        return {
            verdict: 'ported',
            reason:
                `日志显示机型为「${deviceModel}」(设备码 ${codes[0]}),系统版本号 ${systemVersion} 的尾段设备码 ` +
                `${ownerCode} 属于「${ownerNames[0] ?? ownerCode}${ownerNames[1] ? ` / ${ownerNames[1]}` : ''}」,与机型不符`,
            evidence: { deviceCode: codes[0], tail: tails.join('/'), ownerCode, ownerNames },
        };
    }
    return { verdict: 'unknown', reason: `机型与尾段的归属存在多解:${codes.join('/')} vs ${owners.join('/')}` };
}

/** 日志栏目里可作为文本解析的附件链接:github 附件的 .txt / .log;zip 与网盘链接无法解析,交人工。 */
function findTextLogLinks(section) {
    return (section?.match(/https?:\/\/[^\s)>\]"'<]+/g) ?? []).filter((url) =>
        /user-attachments\.com?\/files\/|github\.com\/user-attachments\/files\//i.test(url)
            && /\.(txt|log)(?:[?#].*)?$/i.test(url));
}

async function fetchLogText(url) {
    const response = await fetch(url, { headers: { 'User-Agent': 'issue-rom-check' } });
    if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
    }
    const buffer = await response.arrayBuffer();
    if (buffer.byteLength > 4 * 1024 * 1024) {
        throw new Error(`文件过大(${Math.round(buffer.byteLength / 1024)} KB),跳过`);
    }
    return new TextDecoder('utf-8', { fatal: false }).decode(buffer).slice(0, LOG_TEXT_LIMIT);
}

/**
 * 加载快照:优先从数据分支 rom-data 拉取(每日同步、不污染主分支历史),
 * 失败时回退工作区本地文件(本地测试/手工放置)。都没有返回 null,检测跳过。
 */
async function loadSnapshot(github, context, core) {
    if (github && context) {
        try {
            const { owner, repo } = context.repo;
            const response = await github.rest.repos.getContent({
                owner,
                repo,
                path: '.github/data/rom-codes.json',
                ref: 'rom-data',
            });
            if (response.data?.content) {
                return JSON.parse(Buffer.from(response.data.content, 'base64').toString('utf8'));
            }
        } catch (error) {
            core?.warning(`数据分支快照拉取失败:${error instanceof Error ? error.message : error}`);
        }
    }
    if (!FS.existsSync(SNAPSHOT_PATH)) {
        return null;
    }
    try {
        return JSON.parse(FS.readFileSync(SNAPSHOT_PATH, 'utf8'));
    } catch (error) {
        core?.warning(`快照解析失败:${error instanceof Error ? error.message : error}`);
        return null;
    }
}

function portedComment(judgment) {
    const owner = judgment.evidence?.ownerNames?.[0] ?? judgment.evidence?.ownerCode ?? 'another device';
    return (
        `**官包检测**:${judgment.reason}。\n` +
        '貌似你的系统包非官包,当前 issue 暂不受理,如有不便敬请谅解。' +
        '如果你对当前判定存在差错,可在三天内补充说明你的系统包版本号以及系统界面 APK、系统界面组件 APK 文件,' +
        '过期未补充将自动锁定并关闭 issue。\n\n' +
        '---\n\n' +
        '**Official-ROM check**: ' +
        `the reported device model does not match the ROM's version suffix, which officially belongs to "${owner}".\n` +
        'It seems your system ROM is not an official package, so this issue will not be accepted for now — sorry for the inconvenience. ' +
        'If you believe this detection is wrong, you may reply within 3 days with your exact system version and the ' +
        'com.android.systemui / miui.systemui.plugin APK files. The issue will be locked and closed automatically if no reply is received by then.'
    );
}

function hasLabel(item, label) {
    const expected = label.trim().toLowerCase();
    return item.labels?.some((entry) =>
        (typeof entry === 'string' ? entry : entry?.name)?.trim().toLowerCase() === expected) ?? false;
}

async function listComments(github, owner, repo, number) {
    return github.paginate(github.rest.issues.listComments, {
        owner, repo, issue_number: number, per_page: 100,
    });
}

/**
 * issues.opened / issues.edited / 发起人评论:出现可解析日志时做一次官包检测(已检测过的不再重复)。
 * 不要求 bug 标签——日志栏或评论里有可解析日志都检测;申辩由维护者手动处理,
 * 本脚本不做自动摘标签。
 */
async function checkRomOnOpened({ github, context, core, logSection }) {
    const { owner, repo } = context.repo;
    const issue = context.payload.issue;
    const snapshot = await loadSnapshot(github, context, core);
    if (!snapshot) {
        core.info('官包检测快照不存在,跳过。');
        return;
    }
    const comments = await listComments(github, owner, repo, issue.number);
    if (comments.some((entry) => entry.body?.includes(ROM_MARKER))) {
        core.info('官包检测已执行过,跳过。');
        return;
    }
    const links = findTextLogLinks(logSection);
    if (links.length === 0) {
        core.info('无可解析的文本日志附件(zip / 网盘链接交人工),跳过官包检测。');
        return;
    }
    let environment = null;
    for (const link of links) {
        try {
            environment = extractEnvironment(await fetchLogText(link));
            if (environment) {
                break;
            }
            core.info(`${link} 中未找到环境信息区。`);
        } catch (error) {
            core.warning(`${link} 拉取失败:${error instanceof Error ? error.message : error}`);
        }
    }
    if (!environment) {
        core.info('未能从日志解析出机型与系统版本(LSPosed 裸日志无环境区),跳过。');
        return;
    }
    const judgment = judgeDeviceRom(environment.deviceModel, environment.systemVersion, snapshot);
    core.info(
        `官包检测:${environment.deviceModel} / ${environment.systemVersion} → ${judgment.verdict}(${judgment.reason})`,
    );
    if (judgment.verdict !== 'ported') {
        return;
    }
    await github.rest.issues.createComment({
        owner, repo, issue_number: issue.number,
        body: `${portedComment(judgment)}\n\n${ROM_MARKER}`,
    });
    if (!hasLabel(issue, INVALID_LABEL)) {
        await github.rest.issues.addLabels({
            owner, repo, issue_number: issue.number, labels: [INVALID_LABEL],
        });
    }
    core.notice(`Issue #${issue.number}:疑似非官包(${judgment.reason}),已标记无效。`);
}


module.exports = { checkRomOnOpened, loadSnapshot };
module.exports.testables = {
    ROM_MARKER,
    extractEnvironment,
    extractTailSegments,
    normalizeName,
    judgeDeviceRom,
    findTextLogLinks,
    portedComment,
};
