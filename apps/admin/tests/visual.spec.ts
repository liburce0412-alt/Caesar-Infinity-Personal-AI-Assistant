import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const updateVisualBaselines = process.env.UPDATE_VISUAL_BASELINES === '1'
const visualBaselineDirectory = fileURLToPath(new URL('../../../design/visual-tests', import.meta.url))

for (const width of [320, 375, 414, 768, 1280, 1440]) {
  test(`overview remains operable at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '校园运行脉搏' })).toBeVisible()
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', width)
    const screenshot = await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`admin-${width}.png`), fullPage: true })
    await testInfo.attach(`admin-${width}`, { body: screenshot, contentType: 'image/png' })
    if (updateVisualBaselines) {
      await mkdir(visualBaselineDirectory, { recursive: true })
      await writeFile(resolve(visualBaselineDirectory, `admin-${width}.png`), screenshot)
    }
  })
}

test('mobile navigation and content route work', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/')
  await page.getByRole('button', { name: '打开导航' }).click()
  await page.getByRole('navigation', { name: '管理台导航' }).getByRole('link', { name: '举报审核', exact: true }).click()
  await expect(page.getByRole('heading', { name: '举报审核' })).toBeVisible()
})

test('data search and selection remain usable while unconfigured writes stay disabled', async ({ page }) => {
  await page.goto('/reports')
  await page.getByLabel('搜索当前结果').fill('G-992')
  await expect(page.getByText('商品 #G-992')).toBeVisible()
  await expect(page.getByText('帖子 #P-3821')).toBeHidden()
  await page.getByRole('checkbox', { name: /选择 商品 #G-992/ }).check()
  await expect(page.getByText('已选 1 项')).toBeVisible()
  await expect(page.getByRole('button', { name: '导出所选', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '导出当前页', exact: true })).toBeDisabled()
  await page.getByRole('button', { name: '商品 #G-992', exact: true }).click()
  await expect(page.getByRole('dialog').getByText('举报对象', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog').locator('.action-list button:not(:disabled)')).toHaveCount(0)
})

test('login reports missing environment safely', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('邮箱').fill('admin@campus.ai')
  await page.getByLabel('密码', { exact: true }).fill('password123')
  await page.getByRole('button', { name: /登录管理台/ }).click()
  await expect(page.getByRole('alert')).toContainText('尚未配置')
})

for (const route of ['/invites', '/users', '/content', '/listings', '/orders', '/reports', '/announcements', '/releases', '/audit', '/login']) {
  test(`content and controls stay inside a narrow viewport on ${route}`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width: 320, height: 800 })
    await page.goto(route)
    await expect(page.locator('h1, h2').first()).toBeVisible()
    await expect(page.locator('body')).toHaveJSProperty('scrollWidth', 320)
    const overflow = await page.locator('main button, main input, main select, .login-card input').evaluateAll(elements =>
      elements.filter(element => {
        const box = element.getBoundingClientRect()
        return box.width > 0 && (box.left < -1 || box.right > innerWidth + 1)
      }).map(element => element.textContent || element.getAttribute('aria-label')),
    )
    expect(overflow).toEqual([])
    await testInfo.attach(`admin-route-${route.slice(1)}`, { body: await page.screenshot({ animations: 'disabled', path: testInfo.outputPath(`admin-route-${route.slice(1)}.png`), fullPage: true }), contentType: 'image/png' })
  })
}

test('keyboard navigation shows focus and drawer restores focus after Escape', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/')
  const menu = page.getByRole('button', { name: '打开导航' })
  await menu.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog', { name: '管理台导航菜单' })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('dialog', { name: '管理台导航菜单' })).toBeHidden()
  await expect(menu).toBeFocused()
})

for (const route of ['/users', '/invites']) {
  for (const role of ['admin', 'moderator']) {
  test(`identity errors can be retried without bypassing ${role} permission checks on ${route}`, async ({ page }) => {
    await page.route('**/src/lib/backend.ts', intercepted => intercepted.fulfill({
      contentType: 'application/javascript',
      body: `
        export const backend = null;
        export const isBackendConfigured = false;
        export const api = { rpc: async () => ({ rows: [], total: 0 }) };
        export const inviteSchema = { parse: value => value };
        export async function hasAdminRole() { return false; }
        export async function currentAdmin() {
          if (!window.identityRecovered) throw new Error('身份读取暂时失败');
          return { id: 'test-admin', role: '${role}', is_blocked: false };
        }
      `,
    }))
    await page.goto(route)
    await expect(page.getByRole('alert').filter({ hasText: '身份读取暂时失败' })).toBeVisible({ timeout: 15000 })
    // The protected content remains unavailable until the same identity query succeeds.
    await expect(page.getByText(route === '/users' ? '林屿 / lin@example.edu' : '为第一批伙伴，发出邀请', { exact: true })).toBeHidden()
    await page.evaluate(() => { Object.assign(window, { identityRecovered: true }) })
    await page.getByRole('button', { name: route === '/users' ? '重试' : '刷新', exact: true }).click()
    await expect(page.getByRole('alert').filter({ hasText: '身份读取暂时失败' })).toBeHidden()
    const content = page.getByText(route === '/users' ? '林屿 / lin@example.edu' : '为第一批伙伴，发出邀请', { exact: true })
    if (role === 'admin') await expect(content).toBeVisible()
    else {
      await expect(content).toBeHidden()
      await expect(page.getByRole('alert').filter({ hasText: /仅限管理员/ })).toBeVisible()
      await expect(page.getByRole('button', { name: '刷新', exact: true })).toBeDisabled()
    }
  })
  }
}

test('drawer only dismisses after a completed outside click', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 720 })
  await page.goto('/')
  await page.getByRole('button', { name: '打开导航' }).click()
  const drawer = page.getByRole('dialog', { name: '管理台导航菜单' })
  await page.mouse.move(60, 40)
  await page.mouse.down()
  await page.mouse.move(318, 150, { steps: 6 })
  await page.mouse.up()
  await expect(drawer).toBeVisible()
  await page.mouse.move(318, 300)
  await page.mouse.down()
  await drawer.dispatchEvent('pointercancel', { pointerId: 1 })
  await page.mouse.up()
  await expect(drawer).toBeVisible()
  await page.mouse.click(318, 300)
  await expect(drawer).toBeHidden()
})

for (const failure of ['returned error', 'thrown error']) {
  test(`sign out prevents repeated requests and recovers from a ${failure}`, async ({ page }) => {
    await page.route('**/src/lib/backend.ts', intercepted => intercepted.fulfill({
      contentType: 'application/javascript',
      body: `
        export const backend = { auth: {
          getSession: async () => ({ data: { session: { user: { id: 'test-admin' } } } }),
          onAuthStateChange: () => ({ data: { subscription: { unsubscribe() {} } } }),
          signOut: async () => {
            window.signOutCalls = (window.signOutCalls || 0) + 1;
            if (window.signOutCalls === 1) {
              await new Promise(resolve => { window.releaseSignOut = resolve });
              ${failure === 'thrown error' ? "throw new Error('connection lost');" : "return { error: new Error('service unavailable') };"}
            }
            return { error: null };
          }
        } };
        export const isBackendConfigured = true;
        export const api = { rpc: async () => ({ rows: [], total: 0 }) };
        export const inviteSchema = { parse: value => value };
        export async function currentAdmin() { return { id: 'test-admin', role: 'admin', is_blocked: false }; }
        export async function hasAdminRole(id) { return id === 'test-admin'; }
      `,
    }))
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/')
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    const pending = page.getByRole('button', { name: '正在退出', exact: true })
    await expect(pending).toBeDisabled()
    await pending.evaluate(button => (button as HTMLButtonElement).click())
    expect(await page.evaluate(() => Reflect.get(window, 'signOutCalls'))).toBe(1)
    await page.evaluate(() => Reflect.get(window, 'releaseSignOut')())
    await expect(page.getByRole('alert').filter({ hasText: '退出失败，请重试。' })).toBeVisible()
    await expect(page).not.toHaveURL(/\/login$/)
    await page.getByRole('button', { name: '退出登录', exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    expect(await page.evaluate(() => Reflect.get(window, 'signOutCalls'))).toBe(2)
  })
}
