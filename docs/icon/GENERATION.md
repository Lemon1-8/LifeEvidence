# 女性二次元头像图标

使用 Codex 内置 imagegen 工具生成，未使用 CLI/API 回退。生成日期：2026-09-30。

原始透明 PNG 同时作为应用资源保存在 `app/src/main/res/drawable-nodpi/ic_launcher_avatar.png`；未对人物图像进行二次绘制。Android 自适应图标以独立的浅绿色背景和带留白的头像前景组合，兼容系统圆形、圆角等蒙版。最小 Android 版本为 8.0（API 26）。

布局依据：[Android 自适应图标文档](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。前景四边各保留 1/6 空间，使头像显示于 108 × 108 图层的中央 72 × 72 区域；面部位于中央安全区域。

## 生成提示词

```text
Use case: stylized-concept
Asset type: production Android launcher icon foreground for a Chinese offline reading/reference app named 生活有据 (Life Evidence). This is an original character avatar, not a mockup.
Primary request: a beautifully illustrated female anime avatar, friendly and calm, adult young woman, front-facing bust portrait with an expressive gentle smile, elegant dark forest-green hair, soft green eyes, and a simple ivory top. Cohesive forest-green, sage and warm ivory palette echoing the existing app colors #256455, #D7EADF, #FFF9ED. Clean high-quality Japanese 2D anime illustration with strong clear silhouette, confident clean linework, restrained cel shading, face and eyes readable at a tiny app-icon size. Refined rather than overly detailed.
Composition: square canvas, a single centered head-and-shoulders portrait, entire hair silhouette contained with a small transparent margin at the top and sides. Hair crown and face centered, balanced silhouette. Large head, shoulders only along the lower edge. No hands, props or surrounding decorations. Important face features in the central area to survive circular and squircle Android launcher masking.
Background: genuinely transparent alpha, no colored backdrop, no circle or tile behind the character. The app will supply its own sage background.
Constraints: one original adult female anime character only; no text, no letters, no watermark, no logos, no UI, no border, no icon presentation sheet, no photographic texture. Deliver one final square transparent foreground image.
```
