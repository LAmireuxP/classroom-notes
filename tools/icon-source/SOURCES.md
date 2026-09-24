# 图标素材来源

本目录下的图形素材出处与授权。**CC BY 类授权要求署名**，对应的署名必须同时出现在项目 README 的致谢里。

| 素材 | 作者 | 授权 | 出处 |
| --- | --- | --- | --- |
| `luyin-deer-512.png` | Caro Asercion | CC BY 3.0 | https://game-icons.net/1x1/caro-asercion/deer.html |

## 合成产物

| 素材 | 构成 | 说明 |
| --- | --- | --- |
| `luyin-brand-512.png` | `luyin-deer.svg` 的**头颈部** + 本项目自绘的声波 | 只留头颈是因为整只鹿的元素太多（腿蹄尾在 48dp 下都是噪点），裁剪高度由脚本自动判定。声波由 `tools/icon-deer-sound.py` 绘制，非第三方素材，无额外授权要求。**鹿的形状本身未改动，只是裁掉了身体**。 |
| `luyin-deer-512.png` | `luyin-deer.svg` 重新着色 | 仅有鹿的中间产物，保留供对照 |

重新生成：

```bash
python tools/icon-deer-sound.py                              # 鹿 + 声波 → luyin-brand-512.png
python tools/make-icons.py --source luyin-brand-512.png      # → src/res/mipmap-*/
```

