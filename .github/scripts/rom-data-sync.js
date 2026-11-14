/**
 * 从 HegeKen/miroms(api/v3) 全量机型数据生成官包检测快照 .github/data/rom-codes.json。
 *
 * 快照结构(判定只依赖结构性数据,不含版本号清单):
 * - names:       机型名(中/英及别名,归一化) → 设备码数组
 * - codeTails:   设备码 → 该机型全部官方版本尾段集合(如 OC → [VOCCNXM, WOCCNXM, XOCCNXM, XOCMIXM, ...])
 * - tailOwners:  尾段 → 归属设备码(反向定罪表)
 * - codeNames:   设备码 → 展示名(中/英),判定留言里用
 *
 * 由 .github/workflows/rom-data-sync.yml 每天运行;检测运行时只读快照,不请求外部源。
 */
const FS = require('fs');
const PATH = require('path');

const INDEX_URL = 'https://raw.githubusercontent.com/HegeKen/miroms/master/api/v3/index.json';
const DEVICE_URL_PREFIX = 'https://raw.githubusercontent.com/HegeKen/miroms/master/api/v3/devices/';
const OUTPUT_PATH = PATH.join(__dirname, '..', 'data', 'rom-codes.json');
const CONCURRENCY = 8;

/** 版本号尾段判定:点分段、全大写字母数字、以已知后缀族结尾(XM=正式/DM=演示机/DC/CR/TC=运营商)。 */
const TAIL_SEGMENT = /^[A-Z][A-Z0-9]{1,9}$/;
const SUFFIX_FAMILY = /(XM|DM|DC|CR|TC)$/;

function extractTailSegments(version) {
    return String(version || '')
        .split('.')
        .filter((segment) => TAIL_SEGMENT.test(segment) && SUFFIX_FAMILY.test(segment));
}

/** 机型名归一化:小写、去空白与标点;保留 + (Pro / Pro+ 是不同机型)与中日韩字符(中文名如「小米15」)。 */
function normalizeName(name) {
    return String(name || '')
        .toLowerCase()
        .replace(/[^\p{Script=Han}\p{Letter}\p{Number}+]+/gu, '');
}

/** 一个机型条目贡献的全部别名:中文名、英文名,以及斜杠组合的每一半(如 "Xiaomi 14 Pro / Ti")。 */
function nameAliases(entry) {
    const aliases = new Set();
    for (const name of [entry.name?.zh, entry.name?.en]) {
        if (!name) {
            continue;
        }
        for (const part of String(name).split('/')) {
            const normalized = normalizeName(part);
            if (normalized) {
                aliases.add(normalized);
            }
        }
    }
    return aliases;
}

async function fetchJson(url, attempts = 3) {
    let lastError;
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
        try {
            const response = await fetch(url, { headers: { 'User-Agent': 'rom-data-sync' } });
            if (!response.ok) {
                throw new Error(`HTTP ${response.status}`);
            }
            return await response.json();
        } catch (error) {
            lastError = error;
            if (attempt < attempts) {
                await new Promise((resolve) => setTimeout(resolve, attempt * 1000));
            }
        }
    }
    throw new Error(`${url}: ${lastError?.message ?? lastError}`);
}

/** 简单并发池:逐个调用 worker,保证任意时刻最多 limit 个在飞。 */
async function runPool(items, limit, worker) {
    const failures = [];
    let cursor = 0;
    async function runner() {
        while (cursor < items.length) {
            const item = items[cursor];
            cursor += 1;
            try {
                await worker(item);
            } catch (error) {
                failures.push(`${item.codename}: ${error instanceof Error ? error.message : String(error)}`);
            }
        }
    }
    await Promise.all(Array.from({ length: Math.min(limit, items.length) }, runner));
    return failures;
}

async function main() {
    console.log('拉取机型索引…');
    const index = await fetchJson(INDEX_URL);
    const entries = (Array.isArray(index) ? index : [])
        .filter((entry) => entry?.codename && entry?.code)
        .map((entry) => ({ codename: entry.codename, code: entry.code, name: entry.name || {} }));
    console.log(`机型 ${entries.length} 个(无设备码的已跳过),开始逐个拉取明细…`);

    const names = {};
    const codeTails = {};
    const tailOwners = {};
    const codeNames = {};

    function addTail(code, tail) {
        (codeTails[code] ??= new Set()).add(tail);
        (tailOwners[tail] ??= new Set()).add(code);
    }

    let processed = 0;
    const failures = await runPool(entries, CONCURRENCY, async (entry) => {
        const device = await fetchJson(`${DEVICE_URL_PREFIX}${entry.codename}.json`);
        for (const branch of device.branches ?? []) {
            for (const rom of branch.roms ?? []) {
                for (const tail of extractTailSegments(rom.miui)) {
                    addTail(entry.code, tail);
                }
            }
        }
        for (const alias of nameAliases(entry)) {
            (names[alias] ??= new Set()).add(entry.code);
        }
        const display = [entry.name?.zh, entry.name?.en].filter(Boolean);
        if (display.length) {
            (codeNames[entry.code] ??= new Set());
            display.forEach((name) => codeNames[entry.code].add(name));
        }
        processed += 1;
        if (processed % 40 === 0) {
            console.log(`  已处理 ${processed}/${entries.length}`);
        }
    });

    for (const failure of failures) {
        console.warn(`拉取失败(跳过): ${failure}`);
    }

    // 老机型营销名是「Mi 10 Pro」,部分系统/移植环境上报「Xiaomi 10 Pro」:
    // 为 mi* 数字系列名镜像 xiaomi* 形态(反之亦然),只补空缺键,不覆盖既有机型。
    for (const key of [...Object.keys(names)]) {
        const mirror = key.startsWith('mi') && /^\d/.test(key.slice(2))
            ? `xiaomi${key.slice(2)}`
            : key.startsWith('xiaomi') && /^\d/.test(key.slice(6))
                ? `mi${key.slice(6)}`
                : null;
        if (mirror && !names[mirror]) {
            names[mirror] = new Set(names[key]);
        }
    }

    const snapshot = {
        generatedAt: new Date().toISOString(),
        source: 'HegeKen/miroms api/v3',
        names: Object.fromEntries(
            Object.entries(names)
                .map(([key, codes]) => [key, [...codes].sort()])
                .sort(([a], [b]) => (a < b ? -1 : 1)),
        ),
        codeTails: Object.fromEntries(
            Object.entries(codeTails)
                .map(([key, tails]) => [key, [...tails].sort()])
                .sort(([a], [b]) => (a < b ? -1 : 1)),
        ),
        tailOwners: Object.fromEntries(
            Object.entries(tailOwners)
                .map(([key, codes]) => [key, [...codes].sort()])
                .sort(([a], [b]) => (a < b ? -1 : 1)),
        ),
        codeNames: Object.fromEntries(
            Object.entries(codeNames)
                .map(([key, values]) => [key, [...values].sort()])
                .sort(([a], [b]) => (a < b ? -1 : 1)),
        ),
    };

    FS.mkdirSync(PATH.dirname(OUTPUT_PATH), { recursive: true });
    FS.writeFileSync(OUTPUT_PATH, `${JSON.stringify(snapshot, null, 1)}\n`);
    console.log(
        `快照已写入 ${OUTPUT_PATH}:机型名 ${Object.keys(names).length} 条、设备码 ` +
            `${Object.keys(codeTails).length} 个、尾段 ${Object.keys(tailOwners).length} 个。`,
    );
    if (failures.length > 0) {
        console.warn(`有 ${failures.length} 个机型拉取失败,下次同步会重试。`);
    }
}

module.exports = { extractTailSegments, normalizeName };

if (require.main === module) {
    main().catch((error) => {
        console.error(error);
        process.exit(1);
    });
}
