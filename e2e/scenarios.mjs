// Drives the four manual test scenarios against the live CTS and records one video each.
// Usage: node scenarios.mjs [baseUrl] [outDir] [scenarios, e.g. ABCD]
// Writes to the target's database: reset it afterwards (scripts/reset-demo.sql).
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.argv[2] ?? 'http://localhost:8080';
const OUT = process.argv[3] ?? '../recordings';
const PASSWORD = 'Cts@2026';
const results = [];

const ist = (offsetDays = 0, offsetMonths = 0) => {
	const d = new Date(Date.now() + 5.5 * 3600e3);
	d.setUTCMonth(d.getUTCMonth() + offsetMonths);
	d.setUTCDate(d.getUTCDate() + offsetDays);
	return d.toISOString().slice(0, 10);
};
const TODAY = ist();

function check(scenario, what, actual, expected) {
	const ok = typeof expected === 'function' ? expected(actual) : actual === expected;
	results.push({ scenario, what, ok, actual });
	console.log(`${ok ? 'PASS' : 'FAIL'}  [${scenario}] ${what} -> ${actual}`);
}

// ---------- cheque images, drawn in a browser ----------
async function chequeImages(browser, dir) {
	const page = await browser.newPage({ viewport: { width: 900, height: 400 } });
	const front = (serial, micr, acct, payee, amount) => `
	<body style="margin:0;font-family:Georgia,serif;background:#e9f1f7">
	<div style="width:900px;height:400px;box-sizing:border-box;padding:28px 36px;position:relative;
	  background:repeating-linear-gradient(135deg,#e9f1f7 0 12px,#e1ebf3 12px 24px)">
	  <div style="font-size:26px;font-weight:bold;color:#1f4e79">State Bank of India</div>
	  <div style="font-size:13px;color:#456">Fort Branch, Mumbai · IFSC SBIN0000101</div>
	  <div style="position:absolute;right:36px;top:30px;font-size:16px">Date: ${TODAY.split('-').reverse().join(' ')}</div>
	  <div style="margin-top:44px;font-size:18px">Pay <u style="padding:0 120px 0 8px">${payee}</u> or Bearer</div>
	  <div style="margin-top:22px;font-size:18px">Rupees <u style="padding:0 160px 0 8px">${amount} only</u></div>
	  <div style="position:absolute;right:36px;top:150px;border:2px solid #333;padding:6px 14px;font-size:20px">₹ ${amount}</div>
	  <div style="position:absolute;right:60px;bottom:90px;font-style:italic;font-size:22px">R. Mehta</div>
	  <div style="position:absolute;left:36px;bottom:24px;font-family:'Courier New',monospace;font-size:26px;letter-spacing:4px">
	    ⑈${serial}⑈ ${micr}⑆ ${acct}⑈ 10</div>
	</div></body>`;
	const back = `<body style="margin:0;background:#f4f4f0">
	<div style="width:900px;height:400px;box-sizing:border-box;padding:40px;font-family:Arial">
	  <div style="border:2px dashed #999;width:360px;padding:16px;color:#555">For deposit only<br>
	  CTS Demo Bank · A/c 999000100001<br><br><i>Meera Textiles Pvt Ltd</i></div>
	  <div style="margin-top:120px;color:#aaa;font-size:12px">Do not write below this line</div>
	</div></body>`;
	await page.setContent(front('000123', '400002101', '100201', 'Meera Textiles Pvt Ltd', '25,000'));
	await page.screenshot({ path: path.join(dir, 'front.png') });
	await page.setContent(back);
	await page.screenshot({ path: path.join(dir, 'back.png') });
	await page.close();
	return { front: path.join(dir, 'front.png'), back: path.join(dir, 'back.png') };
}

// ---------- helpers ----------
async function newRecordedPage(browser, name) {
	const context = await browser.newContext({
		viewport: { width: 1280, height: 800 },
		recordVideo: { dir: path.join(OUT, '_raw', name), size: { width: 1280, height: 800 } },
	});
	// A caption bar that survives navigation, so the video explains itself.
	await context.addInitScript(() => {
		const paint = () => {
			const text = localStorage.getItem('caption');
			if (!text || !document.body) return;
			let bar = document.getElementById('__caption');
			if (!bar) {
				bar = document.createElement('div');
				bar.id = '__caption';
				bar.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:99999;background:rgba(17,24,39,.92);'
					+ 'color:#fff;font:600 17px/1.4 system-ui;padding:12px 20px;text-align:center';
				document.body.appendChild(bar);
			}
			bar.textContent = text;
		};
		document.addEventListener('DOMContentLoaded', paint);
		window.__paintCaption = paint;
	});
	const page = await context.newPage();
	page.setDefaultTimeout(120_000);
	return { context, page };
}

async function say(page, text) {
	await page.evaluate(t => { localStorage.setItem('caption', t); window.__paintCaption?.(); }, text);
	await page.waitForTimeout(1400);
}

async function login(page, user) {
	await page.goto(`${BASE}/login`);
	await say(page, `Sign in as ${user}`);
	await page.fill('input[name=username]', user);
	await page.fill('input[name=password]', PASSWORD);
	await page.click('button:has-text("Sign in")');
	await page.waitForURL(u => !u.pathname.startsWith('/login'));
}

async function logout(page) {
	await page.click('button:has-text("Sign out")');
	await page.waitForURL(/\/login/);
}

async function capture(page, img, c) {
	await page.goto(`${BASE}/cheques/new`);
	await page.fill('input[name=serial]', c.serial);
	await page.fill('input[name=micrCode]', c.micr);
	await page.fill('input[name=accountNo]', c.acct);
	await page.fill('input[name=txCode]', '10');
	await page.fill('input[name=amount]', c.amount);
	await page.fill('input[name=chequeDate]', c.date ?? TODAY);
	await page.fill('input[name=payeeName]', c.payee ?? 'Meera Textiles Pvt Ltd');
	await page.setInputFiles('input[name=front]', img.front);
	await page.setInputFiles('input[name=back]', img.back);
	await page.click('form.card button:has-text("Capture")');
	await page.waitForLoadState('load');
	const m = page.url().match(/\/cheques\/(\d+)/);
	return m ? Number(m[1]) : null;
}

async function status(page, id) {
	if (!page.url().endsWith(`/cheques/${id}`)) await page.goto(`${BASE}/cheques/${id}`);
	return (await page.locator('.titlebar .badge').first().textContent()).trim();
}

async function returnCode(page, id) {
	await page.goto(`${BASE}/cheques/${id}`);
	const reason = page.locator('p.reason');
	return (await reason.count()) ? (await reason.textContent()).replace(/\s+/g, ' ').trim() : '';
}

async function present(page) {
	await page.goto(`${BASE}/`);
	await page.click('button:has-text("Present ready items")');
	await page.waitForLoadState('load');
}

async function finish(context, page, name) {
	await page.waitForTimeout(1000);
	const video = page.video();
	await context.close();
	const raw = await video.path();
	const target = path.join(OUT, `${name}.webm`);
	fs.renameSync(raw, target);
	console.log(`video: ${target}`);
}

// ---------- scenarios ----------
async function scenarioA(browser, img) {
	const S = 'A happy path';
	const { context, page } = await newRecordedPage(browser, 'A');
	await login(page, 'maker');
	await say(page, 'A1 · maker captures cheque 000123 on SBI Mumbai for ₹25,000');
	const id = await capture(page, img, { serial: '000123', micr: '400002101', acct: '100201', amount: '25000' });
	check(S, 'captured cheque goes to READY', await status(page, id), 'READY');
	await say(page, 'A2 · maker captures the exact same cheque again');
	const dup = await capture(page, img, { serial: '000123', micr: '400002101', acct: '100201', amount: '25000' });
	check(S, 'second capture is rejected', await status(page, dup), 'REJECTED');
	check(S, 'rejection says duplicate', await returnCode(page, dup), r => r.includes('Duplicate'));
	await logout(page);

	await login(page, 'ops');
	await say(page, 'A3 · ops presents ready items: each is signed and sent to the clearing house');
	await present(page);
	check(S, 'presented', await status(page, id), 'PRESENTED');
	await say(page, 'Presented: item hash, RSA signing key and expiry are recorded');
	await logout(page);

	await login(page, 'drawee');
	await page.goto(`${BASE}/inward`);
	await say(page, 'A4 · drawee bank sees it in inward clearing, compares the signature, confirms');
	await page.goto(`${BASE}/cheques/${id}`);
	await page.click('button:has-text("Confirm")');
	await page.waitForLoadState('load');
	check(S, 'confirmed', await status(page, id), 'CONFIRMED');
	await logout(page);

	await login(page, 'ops');
	await page.goto(`${BASE}/settlement`);
	await say(page, 'A5 · ops runs settlement: payee credited, net positions per bank');
	await page.click('button:has-text("Settle")');
	await page.waitForLoadState('load');
	await say(page, 'SBI pays ₹25,000 · CTS Demo Bank receives ₹25,000 · positions sum to zero');
	check(S, 'settled', await status(page, id), 'SETTLED');
	await page.goto(`${BASE}/ledger`);
	await say(page, 'A6 · ledger: four postings for this cheque, debits equal credits');
	check(S, 'ledger balanced', (await page.locator('p.notice').first().textContent()).trim(), t => t.startsWith('Balanced'));
	await finish(context, page, 'A-happy-path');
}

async function scenarioB(browser, img) {
	const S = 'B maker-checker + Positive Pay';
	const { context, page } = await newRecordedPage(browser, 'B');
	await login(page, 'drawee');
	await page.goto(`${BASE}/positive-pay`);
	await say(page, 'B1 · drawee: the drawer registered cheque 000310 for ₹6,00,000 under Positive Pay');
	const row = page.locator('table.grid tr', { hasText: '000310' }).first();
	const ppsDate = (await row.locator('td').nth(3).textContent()).trim();
	await logout(page);

	await login(page, 'maker');
	await say(page, `B2 · maker captures 000310 on Axis Chennai, ₹6,00,000, dated ${ppsDate} as registered`);
	const id = await capture(page, img, { serial: '000310', micr: '600211104', acct: '211044', amount: '600000', date: ppsDate });
	check(S, 'high value is held', await status(page, id), 'PENDING APPROVAL');
	await logout(page);

	await login(page, 'admin');
	await say(page, 'B3 · admin (all roles) captures a ₹1,50,000 cheque, then tries to approve it');
	const self = await capture(page, img, { serial: '000901', micr: '110240102', acct: '240511', amount: '150000' });
	await page.click('button:has-text("Approve")');
	await page.waitForLoadState('load');
	const err = (await page.locator('p.error').first().textContent()).trim();
	await say(page, 'Refused: the maker can never approve their own item');
	check(S, 'self-approval refused', err, e => e.includes('Maker-checker'));
	check(S, 'still pending', await status(page, self), 'PENDING APPROVAL');
	await logout(page);

	await login(page, 'checker');
	await page.goto(`${BASE}/approvals`);
	await say(page, 'B4 · checker opens the approvals queue and approves 000310');
	await page.goto(`${BASE}/cheques/${id}`);
	await page.click('button:has-text("Approve")');
	await page.waitForLoadState('load');
	check(S, 'approved to READY', await status(page, id), 'READY');
	await logout(page);

	await login(page, 'ops');
	await say(page, 'B5 · ops presents; the drawee checks it against Positive Pay');
	await present(page);
	check(S, 'passes Positive Pay', await status(page, id), 'PRESENTED');
	await logout(page);

	await login(page, 'drawee');
	await page.goto(`${BASE}/cheques/${id}`);
	await say(page, 'B6 · it matched the registration, so the drawee confirms');
	await page.click('button:has-text("Confirm")');
	await page.waitForLoadState('load');
	check(S, 'confirmed', await status(page, id), 'CONFIRMED');
	await finish(context, page, 'B-maker-checker-positive-pay');
}

async function scenarioC(browser, img) {
	const S = 'C failures';
	const { context, page } = await newRecordedPage(browser, 'C');
	await login(page, 'maker');
	const cases = [
		{ key: 'stop', label: 'C1 · 000045 on SBI: the drawer stopped this cheque', serial: '000045', micr: '400002101', acct: '100201', amount: '10000' },
		{ key: 'funds', label: 'C2 · ₹20,000 on ICICI Bengaluru, balance is ₹15,000', serial: '000201', micr: '560229103', acct: '229812', amount: '20000' },
		{ key: 'closed', label: 'C3 · a cheque on a closed SBI account', serial: '000202', micr: '400002105', acct: '100777', amount: '1000' },
		{ key: 'pps', label: 'C4 · ₹6,00,000 on Axis with no Positive Pay registration', serial: '000203', micr: '600211104', acct: '211044', amount: '600000' },
		{ key: 'sig', label: 'C5 · ₹75,000 on HDFC Delhi: passes the automatic checks', serial: '000204', micr: '110240102', acct: '240511', amount: '75000' },
		{ key: 'stale', label: 'C6 · dated four months ago', serial: '000205', micr: '110240102', acct: '240511', amount: '1000', date: ist(0, -4) },
		{ key: 'post', label: 'C7 · dated tomorrow', serial: '000206', micr: '110240102', acct: '240511', amount: '1000', date: ist(1) },
		{ key: 'bank', label: 'C8 · bank code 777 is not a participant', serial: '000207', micr: '400777101', acct: '100201', amount: '1000' },
	];
	const ids = {};
	for (const c of cases) {
		await say(page, c.label);
		ids[c.key] = await capture(page, img, c);
	}
	check(S, 'stale rejected at capture', await returnCode(page, ids.stale), r => r.includes('Stale'));
	check(S, 'post-dated rejected at capture', await returnCode(page, ids.post), r => r.includes('Post-dated'));
	check(S, 'unknown bank rejected', await returnCode(page, ids.bank), r => r.includes('Unknown drawee bank'));

	await say(page, 'C9 · an 8-digit MICR code never gets past the form');
	await page.goto(`${BASE}/cheques/new`);
	await capture(page, img, { serial: '000208', micr: '40000210', acct: '100201', amount: '1000' });
	check(S, 'malformed MICR refused', (await page.locator('p.error').first().textContent()).trim(), e => e.includes('9 digits'));
	await logout(page);

	await login(page, 'checker');
	await say(page, 'C10 · checker approves the ₹6,00,000 item so it can be presented');
	await page.goto(`${BASE}/cheques/${ids.pps}`);
	await page.click('button:has-text("Approve")');
	await page.waitForLoadState('load');
	await logout(page);

	await login(page, 'ops');
	await say(page, 'C11 · ops presents everything; the drawee bank\'s rules run on receipt');
	await present(page);
	await logout(page);

	await login(page, 'drawee');
	check(S, 'stop payment returns 20', await returnCode(page, ids.stop), r => r.includes('Return 20'));
	await say(page, 'Returned 20: payment stopped by drawer');
	check(S, 'insufficient funds returns 01', await returnCode(page, ids.funds), r => r.includes('Return 01'));
	await say(page, 'Returned 01: funds insufficient');
	check(S, 'closed account returns 88', await returnCode(page, ids.closed), r => r.includes('Account closed'));
	await say(page, 'Returned 88: account closed');
	check(S, 'missing Positive Pay returns 88', await returnCode(page, ids.pps), r => r.includes('Positive Pay'));
	await say(page, 'Returned 88: Positive Pay registration required at this amount');
	await page.goto(`${BASE}/cheques/${ids.sig}`);
	await say(page, 'C12 · drawee compares the signature with the specimen: it differs, return 12');
	await page.selectOption('select[name=code]', '12');
	await page.fill('form.inline input[name=reason]', 'Signature does not match specimen');
	await page.click('button:has-text("Return")');
	await page.waitForLoadState('load');
	check(S, 'manual return 12', await returnCode(page, ids.sig), r => r.includes('Return 12'));
	await page.goto(`${BASE}/?status=RETURNED`);
	await say(page, 'Every failure, with its NPCI reason code');
	await finish(context, page, 'C-failures');
}

async function scenarioD(browser) {
	const S = 'D access control';
	const { context, page } = await newRecordedPage(browser, 'D');
	await login(page, 'maker');
	for (const p of ['/approvals', '/inward', '/positive-pay']) {
		await say(page, `D · maker types ${p} into the address bar`);
		const res = await page.goto(`${BASE}${p}`);
		check(S, `maker on ${p}`, res.status(), 403);
	}
	await say(page, 'D · the JSON API the mobile app would use');
	const api = await page.goto(`${BASE}/api/cheques`);
	const body = await api.json();
	check(S, 'API returns cheques', Array.isArray(body) && body.length > 0, true);
	await page.waitForTimeout(1500);
	await page.goto(`${BASE}/`);
	await logout(page);
	await say(page, 'D · signed out: everything redirects to sign-in');
	await page.goto(`${BASE}/api/cheques`);
	check(S, 'anonymous is sent to login', new URL(page.url()).pathname, '/login');
	await finish(context, page, 'D-access-control');
}

// ---------- run ----------
fs.mkdirSync(OUT, { recursive: true });
const browser = await chromium.launch({ slowMo: 250 });
try {
	console.log(`waking ${BASE} ...`);
	const warm = await browser.newPage();
	await warm.goto(`${BASE}/actuator/health`, { timeout: 180_000 });
	await warm.close();
	const img = await chequeImages(browser, OUT);
	const only = (process.argv[4] ?? 'ABCD').split('');
	if (only.includes('A')) await scenarioA(browser, img);
	if (only.includes('B')) await scenarioB(browser, img);
	if (only.includes('C')) await scenarioC(browser, img);
	if (only.includes('D')) await scenarioD(browser);
}
finally {
	await browser.close();
	try { fs.rmSync(path.join(OUT, '_raw'), { recursive: true, force: true }); } catch { /* a video still open */ }
	const failed = results.filter(r => !r.ok);
	console.log(`\n${results.length - failed.length}/${results.length} checks passed`);
	failed.forEach(f => console.log(`FAILED [${f.scenario}] ${f.what}: got ${f.actual}`));
}
