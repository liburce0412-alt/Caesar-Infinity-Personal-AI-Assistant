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
    const screenshot = await page.screenshot({ fullPage: true })
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
  await expect(page.getByRole('button', { name: /逐项处理/ })).toBeDisabled()
  await expect(page.getByRole('button', { name: /导出举报/ })).toBeDisabled()
})

test('login reports missing environment safely', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('邮箱').fill('admin@campus.ai')
  await page.getByLabel('密码').fill('password123')
  await page.getByRole('button', { name: /登录管理台/ }).click()
  await expect(page.getByRole('alert')).toContainText('尚未配置 Supabase')
})
