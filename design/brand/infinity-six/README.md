# Caesar∞ 六重无限标识

两份原稿由产品所有者于 2026-10-07 提供，灵感为六个无限符号交织。

- `pearl-original.png`：白色珠光版，用作桌面图标。
- `metal-original.png`：黑银金属版，用作开屏最终标识和应用内品牌标识。

Android 使用去除背景后的透明资源，保留六瓣轮廓、交织关系和原有材质。原稿保留以便后续制作。

冷启动约 2.05 秒：黑银与珠白两条独立光带先在两侧展开，约 280–1050ms 沿 Bézier 曲线交织，交叉处形成前后穿行关系；约 850–1180ms 平滑收束为用户提供的完整黑银标识，随后微回弹和一次扫光。约 1050–1710ms，原创尖角手写 `CAESAR∞` 字样用单一连续路径逐笔签出。最后 300ms 连续退场露出首页。

光带与签名均为原生 Compose Canvas 路径，控制点、材质与 PathMeasure 在绘制缓存中复用；标识位图只在入场时加载，不逐帧解码。日常返回、通知跳转与分享入口不重播；动画关闭时直接显示应用内容。播放最多 2200ms 放行，系统启动窗口未发送就绪信号时仍以 1100ms 放行，不阻塞导航。

应用入场与首页共享同一实例的 SpectraBackdrop，跟随用户选择的环境、明暗、渲染质量与流光配置。只让首页前景内容在最后 300ms 渐入，背景持续运行，无第二个背景渲染器或切换时重启。系统在应用绘制前的启动窗口仍使用原生背景及完整标识。

签名字形参考 Cybertruck 字标的尖角、斜势与涂鸦笔触方向，由本项目自行绘制 `CAESAR∞` 路径。相邻字母基线约有 30–33 个路径单位的高低错落，字高和顶线也不齐平；尖角连笔一路延伸到无限符号，保持一笔签出的节奏。没有使用、描摹或下载 Tesla 字标与字体文件，不声称 Tesla 授权或关联。

设计参考：

- [Android SplashScreen](https://developer.android.com/develop/ui/views/launch/splash-screen)：遵循系统启动窗口与应用内容之间的交接，不另外开启启动 Activity。
- [Material motion](https://m1.material.io/motion/material-motion.html)：运动中的连续性与元素编排。
- [Rive 移动应用案例](https://rive.app/use-cases/mobile-apps)：参考 Duolingo、Notion 等品牌动效的表现思路；本实现使用原生 Compose，不引入 Rive 运行库。
- [Tesla 官方 Cybertruck Reflective Tee](https://shop.tesla.com/en_gb/product/men_s-cybertruck-reflective-tee)：官方将其称为 Cybertruck graffiti wordmark，用于确认用户所指的尖角涂鸦风格；本项目使用独立原创的品牌签名路径。

原有彩色流光保持不变，继续使用系统提供的彩色 `outer_glow`。紫白青蓝流光使用原生 RemoteViews 展开卡片，仅在系统允许本应用自定义布局时提供；本次小米 15 Pro 的额外白名单拒绝此路径，正式版自动保留原生彩色样式。正式版不包含设计预览、测试通知按钮或实验设置。
