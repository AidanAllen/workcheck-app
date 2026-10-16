# -*- coding: utf-8 -*-
"""从一张原图生成 Android 全套启动图标。

产出（写入 --res 指向的 res 目录）：
  mipmap-<density>/ic_launcher.png            传统方形图标（圆角，Android 7 及以下用）
  mipmap-<density>/ic_launcher_round.png      传统圆形图标
  mipmap-<density>/ic_launcher_foreground.png 自适应图标前景（内容居中留安全边）
  mipmap-<density>/ic_launcher_background.png 自适应图标背景（模糊铺满，兼容方形遮罩）

用法：
  python scripts/gen-icons.py 原图.png
  python scripts/gen-icons.py 原图.png --res mobile/android/app/src/main/res --assets mobile/resources
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

# 密度 -> 边长（px）。传统图标 48dp 基准，自适应图标 108dp 基准。
LEGACY = [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]
ADAPTIVE = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]

# 自适应图标内容占画布的比例。系统遮罩只保证中间 66.7% 可见，
# 取 0.80：既让画面铺得满，又保证 66.7% 圆内的四角不越界。
CONTENT_RATIO = 0.80
# 传统方形图标的圆角占边长比例（对齐 Android 图标的视觉惯例）
LEGACY_RADIUS = 0.22
# 背景层模糊半径占画布比例：够糊才像色晕，不至于看出是拉伸的图
BG_BLUR_RATIO = 0.05

SUPERSAMPLE = 4  # 遮罩超采样倍数，靠降采样拿到抗锯齿边缘


def load_square(path):
    """读图并归一成正方形 RGBA，去掉四周纯透明边。"""
    im = Image.open(path).convert("RGBA")
    alpha = im.getchannel("A")
    if alpha.getextrema()[0] < 255:
        bbox = alpha.getbbox()
        if bbox:
            im = im.crop(bbox)
    w, h = im.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        im = im.crop((left, top, left + side, top + side))
    return im


def cover(im, size):
    """等比放大填满目标尺寸，居中裁切（不改变宽高比）。"""
    tw, th = size
    sw, sh = im.size
    scale = max(tw / sw, th / sh)
    resized = im.resize((max(1, round(sw * scale)), max(1, round(sh * scale))), Image.Resampling.LANCZOS)
    rw, rh = resized.size
    left = (rw - tw) // 2
    top = (rh - th) // 2
    return resized.crop((left, top, left + tw, top + th))


def contain(im, size):
    """等比缩小放进目标尺寸，居中留透明边。"""
    tw, th = size
    sw, sh = im.size
    scale = min(tw / sw, th / sh)
    resized = im.resize((max(1, round(sw * scale)), max(1, round(sh * scale))), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", size, (0, 0, 0, 0))
    canvas.paste(resized, ((tw - resized.width) // 2, (th - resized.height) // 2), resized)
    return canvas


def rounded_mask(size, radius_ratio):
    """抗锯齿圆角矩形遮罩。"""
    w, h = size
    big = (w * SUPERSAMPLE, h * SUPERSAMPLE)
    mask = Image.new("L", big, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, big[0] - 1, big[1] - 1], radius=int(big[0] * radius_ratio), fill=255
    )
    return mask.resize(size, Image.Resampling.LANCZOS)


def circle_mask(size):
    """抗锯齿正圆遮罩。"""
    big = (size[0] * SUPERSAMPLE, size[1] * SUPERSAMPLE)
    mask = Image.new("L", big, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, big[0] - 1, big[1] - 1], fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)


def apply_mask(im, mask):
    out = im.copy()
    out.putalpha(mask)
    return out


def save(im, path, colors=None):
    """存 PNG。colors 给定就做调色板量化——插画渐变用 256 色肉眼看不出差别，
    体积却能砍掉一多半；FASTOCTREE 是唯一支持带 alpha 量化的算法。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out = im
    if colors:
        out = im.convert("RGBA").quantize(colors=colors, method=Image.Quantize.FASTOCTREE,
                                          dither=Image.Dither.FLOYDSTEINBERG)
    out.save(path, "PNG", optimize=True)
    return os.path.getsize(path)


def build_foreground(src, px):
    """自适应前景：内容缩到 CONTENT_RATIO 居中，四周透明。"""
    inner = max(1, round(px * CONTENT_RATIO))
    content = contain(src, (inner, inner))
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    off = (px - inner) // 2
    canvas.paste(content, (off, off), content)
    return canvas


def build_background(src, px):
    """自适应背景：铺满 + 模糊。圆形遮罩下看不见，方形遮罩/视差动效时露出色晕。"""
    filled = cover(src, (px, px))
    return filled.filter(ImageFilter.GaussianBlur(radius=max(1.0, px * BG_BLUR_RATIO)))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("src", help="源图片路径")
    ap.add_argument("--res", default="mobile/android/app/src/main/res", help="Android res 目录")
    ap.add_argument("--assets", default="", help="素材留存目录（输出压缩版原图）")
    ap.add_argument("--skip-legacy", action="store_true", help="只出自适应图标")
    args = ap.parse_args()

    if not os.path.isfile(args.src):
        print("找不到源图：%s" % args.src, file=sys.stderr)
        return 1

    src = load_square(args.src)
    print("源图：%s  归一后 %dx%d" % (args.src, src.width, src.height))
    legacy_bytes = adaptive_bytes = 0

    for density, px in ADAPTIVE:
        d = os.path.join(args.res, "mipmap-" + density)
        adaptive_bytes += save(build_foreground(src, px), os.path.join(d, "ic_launcher_foreground.png"), 256)
        adaptive_bytes += save(build_background(src, px), os.path.join(d, "ic_launcher_background.png"), 64)
        print("  自适应 %-8s %3dpx" % (density, px))

    if not args.skip_legacy:
        for density, px in LEGACY:
            d = os.path.join(args.res, "mipmap-" + density)
            base = cover(src, (px, px))
            legacy_bytes += save(apply_mask(base, rounded_mask((px, px), LEGACY_RADIUS)),
                                 os.path.join(d, "ic_launcher.png"), 256)
            legacy_bytes += save(apply_mask(base, circle_mask((px, px))),
                                 os.path.join(d, "ic_launcher_round.png"), 256)
        print("  传统图标 5 档密度完成")

    if args.assets:
        os.makedirs(args.assets, exist_ok=True)
        # 压缩版原图：256 色量化 + 优化的 PNG，去掉 24bit 里用不到的色阶
        png_path = os.path.join(args.assets, "logo.png")
        quant = src.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT,
                                            dither=Image.Dither.FLOYDSTEINBERG)
        quant.save(png_path, "PNG", optimize=True)
        webp_path = os.path.join(args.assets, "logo.webp")
        src.convert("RGB").save(webp_path, "WEBP", quality=88, method=6)
        raw = os.path.getsize(args.src)
        png_size = os.path.getsize(png_path)
        webp_size = os.path.getsize(webp_path)
        print("压缩版：原 %d KB -> PNG %d KB / WebP %d KB"
              % (raw // 1024, png_size // 1024, webp_size // 1024))
        for p in (png_path, webp_path):
            print("  " + p)

    print("图标体积合计：自适应 %d KB + 传统 %d KB" % (adaptive_bytes // 1024, legacy_bytes // 1024))
    return 0


if __name__ == "__main__":
    sys.exit(main())
