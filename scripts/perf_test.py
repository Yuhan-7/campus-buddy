# -*- coding: utf-8 -*-
"""性能与压力测试（只用 Python 标准库）。

前提：系统已用 docker compose up -d 启动，演示数据已生成（DEMO_DATA=true）。
用法：在项目根目录执行  python3 scripts/perf_test.py
可选：python3 scripts/perf_test.py --base http://localhost:8088 --levels 1,10,50,100,200

测试内容：
  性能测试——各核心接口在不同并发下的平均 / P95 / 最大响应时间、吞吐量和错误率；
  压力测试——逐级加大并发（最高默认 200），观察响应时间和错误率在哪一级开始明显恶化。
结果打印为 Markdown 表格，并保存到 test-results/perf-时间.md。
"""
import argparse, json, os, platform, statistics, subprocess, threading, time, urllib.request, urllib.error
from concurrent.futures import ThreadPoolExecutor
from http.cookiejar import CookieJar

ap = argparse.ArgumentParser()
ap.add_argument('--base', default='http://localhost:8088')
ap.add_argument('--levels', default='1,10,50,100,200')
ap.add_argument('--account', default='')
ap.add_argument('--password', default='')
ap.add_argument('--per-worker', type=int, default=20, help='每个并发线程发送的请求数')
args = ap.parse_args()
BASE = args.base.rstrip('/')
LEVELS = [int(x) for x in args.levels.split(',')]


def opener_with_login(account='test@buddy.demo', password='test12345'):
    jar = CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    body = json.dumps({'account': account, 'password': password}).encode()
    req = urllib.request.Request(BASE + '/api/login', data=body, method='POST', headers={'Content-Type': 'application/json'})
    op.open(req, timeout=15).read()
    return op


def one(op, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method, headers={'Content-Type': 'application/json'})
    t0 = time.perf_counter()
    try:
        with op.open(req, timeout=30) as r:
            r.read(); ok = 200 <= r.status < 300
    except urllib.error.HTTPError as e:
        e.read(); ok = False
    except Exception:
        ok = False
    return (time.perf_counter() - t0) * 1000, ok


def run(name, make_opener, method, path, body, levels, per_worker):
    rows = []
    for c in levels:
        openers = [make_opener() for _ in range(c)]
        lat, errs = [], [0]
        lock = threading.Lock()

        def worker(op):
            for _ in range(per_worker):
                ms, ok = one(op, method, path, body)
                with lock:
                    lat.append(ms)
                    if not ok: errs[0] += 1
        t0 = time.perf_counter()
        with ThreadPoolExecutor(max_workers=c) as ex:
            list(ex.map(worker, openers))
        wall = time.perf_counter() - t0
        lat.sort(); n = len(lat)
        rows.append(dict(scene=name, conc=c, n=n, avg=statistics.mean(lat), p95=lat[max(0, int(n * 0.95) - 1)],
                         mx=lat[-1], tps=n / wall, err=errs[0] / n * 100))
        r = rows[-1]
        print(f"  {name:<14} 并发 {c:>3}：{n} 次，平均 {r['avg']:.0f} ms，P95 {r['p95']:.0f} ms，最大 {r['mx']:.0f} ms，"
              f"吞吐 {r['tps']:.1f} 次/秒，错误率 {r['err']:.1f}%")
    return rows


def data_counts():
    sql = ("SELECT (SELECT COUNT(*) FROM users),(SELECT COUNT(*) FROM needs),(SELECT COUNT(*) FROM needs WHERE status='open'),"
           "(SELECT COUNT(*) FROM invitations),(SELECT COUNT(*) FROM need_views)")
    try:
        out = subprocess.run(['docker', 'compose', 'exec', '-T', 'db', 'sh', '-c',
                              f'mysql -N -ubuddy -p"$MYSQL_PASSWORD" campus_buddy -e "{sql}" 2>/dev/null'],
                             capture_output=True, text=True, timeout=30).stdout.split()
        return dict(zip(['用户', '需求', '开放需求', '邀请', '浏览记录'], out))
    except Exception as e:
        return {'说明': f'未能读取（{e}）'}


def main():
    anon = lambda: urllib.request.build_opener()
    plain = urllib.request.build_opener()
    needs = json.loads(plain.open(BASE + '/api/needs', timeout=15).read())
    need_id = (needs.get('needs') or needs.get('items') or [{}])[0].get('id')
    tries = [(args.account, args.password)] if args.account else []
    tries += [('test@buddy.demo', 'test12345')] + [(f'demo{i:02d}@buddy.demo', 'demo12345') for i in range(1, 10)]
    acct = open_need = None
    for a, pw in tries:
        try:
            me = opener_with_login(a, pw)
        except urllib.error.HTTPError as e:
            print(f'  账号 {a} 登录失败：HTTP {e.code} {e.read().decode("utf-8", "ignore")[:80]}，换下一个账号')
            continue
        mine = json.loads(me.open(BASE + '/api/my-needs', timeout=15).read())
        open_need = next((n['id'] for n in mine.get('needs', []) if n.get('status') == 'open'), None)
        if open_need:
            acct = (a, pw); break
    if not acct:
        raise SystemExit('没有可用于测试匹配推荐的账号，请用 --account 邮箱 --password 密码 指定一个有开放需求的账号')
    print(f'  匹配推荐使用账号 {acct[0]}，需求 id={open_need}')

    env = {
        '时间': time.strftime('%Y-%m-%d %H:%M:%S'),
        '被测地址': BASE,
        '客户端': f"{platform.system()} {platform.machine()}，Python {platform.python_version()}，CPU 核数 {os.cpu_count()}",
        '服务端': 'Docker Compose：frontend(Nginx) + backend(Spring Boot) + db(MySQL 8.4)，与客户端同一台电脑',
        '数据量': data_counts(),
        '每个并发线程请求数': args.per_worker,
    }
    print('测试环境：', json.dumps(env, ensure_ascii=False, indent=1))

    rows = []
    print('\n[性能 / 压力] 公开接口（无需登录）')
    rows += run('需求广场列表', anon, 'GET', '/api/needs', None, LEVELS, args.per_worker)
    rows += run('热门主题', anon, 'GET', '/api/trending', None, LEVELS, args.per_worker)
    rows += run('需求详情', anon, 'GET', f'/api/needs/{need_id}', None, LEVELS, args.per_worker)
    print('\n[性能 / 压力] 登录后接口（每个线程先登录一次，不计入耗时）')
    rows += run('匹配推荐', lambda: opener_with_login(*acct), 'GET', f'/api/recommend/{open_need}', None, LEVELS, args.per_worker)
    print('\n[性能] 登录（BCrypt 强度 12，本身较慢，只测低并发）')
    rows += run('登录', anon, 'POST', '/api/login', {'account': acct[0], 'password': acct[1]},
                [c for c in LEVELS if c <= 10], 5)

    lines = ['# 性能与压力测试结果', '']
    for k, v in env.items():
        lines.append(f"- {k}：{v if not isinstance(v, dict) else '，'.join(f'{a} {b}' for a, b in v.items())}")
    lines += ['', '| 场景 | 并发数 | 请求数 | 平均(ms) | P95(ms) | 最大(ms) | 吞吐(次/秒) | 错误率 |', '|---|---|---|---|---|---|---|---|']
    for r in rows:
        lines.append(f"| {r['scene']} | {r['conc']} | {r['n']} | {r['avg']:.0f} | {r['p95']:.0f} | {r['mx']:.0f} | {r['tps']:.1f} | {r['err']:.1f}% |")
    os.makedirs('test-results', exist_ok=True)
    path = time.strftime('test-results/perf-%Y%m%d-%H%M%S.md')
    open(path, 'w', encoding='utf-8').write('\n'.join(lines) + '\n')
    print('\n' + '\n'.join(lines))
    print(f'\n结果已保存到 {path} ，把这个文件的内容发给 Claude 即可。')


if __name__ == '__main__':
    main()
