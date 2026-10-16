"""Prototype of the stage-1 pipeline. Mirrors Processor.kt step by step."""
import sys
import cv2
import numpy as np

TARGET = 0.5      # target mid brightness for local tone mapping
NEUTRAL_CHROMA = 14.0
MIN_NEUTRAL_FRACTION = 0.05
WB_MIN, WB_MAX = 0.85, 1.2
SAT = 1.35
ALPHA = 0.45      # strength of the correction (0 = off, 1 = full flatten)
GAIN_MIN, GAIN_MAX = 0.65, 2.2
CLAHE_CLIP = 2.0
SHARP = 0.35


def gray_world(rgb):
    """Gray World over near-neutral pixels only, so a blue sky is not 'corrected' away."""
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2Lab)
    l, a, b = cv2.split(lab)
    chroma = np.hypot(a.astype(np.float32) - 128.0, b.astype(np.float32) - 128.0)
    mask = ((chroma < NEUTRAL_CHROMA) & (l > 40) & (l < 235)).astype(np.uint8)
    if mask.mean() < MIN_NEUTRAL_FRACTION:
        return rgb
    means = cv2.mean(rgb, mask)[:3]
    gray = sum(means) / 3.0
    chans = cv2.split(rgb)
    out = []
    for c, m in zip(chans, means):
        g = float(np.clip(gray / max(m, 1e-3), WB_MIN, WB_MAX))
        out.append(cv2.convertScaleAbs(c, alpha=g))
    return cv2.merge(out)


def boost_saturation(rgb, factor):
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    h, s, v = cv2.split(hsv)
    s = cv2.convertScaleAbs(s, alpha=factor)
    return cv2.cvtColor(cv2.merge([h, s, v]), cv2.COLOR_HSV2RGB)


def local_tone_map(l8):
    h, w = l8.shape
    lf = l8.astype(np.float32) / 255.0
    small = cv2.resize(lf, (max(w // 8, 1), max(h // 8, 1)), interpolation=cv2.INTER_AREA)
    sigma = max(small.shape) / 20.0
    small = cv2.GaussianBlur(small, (0, 0), sigma)
    illum = cv2.resize(small, (w, h), interpolation=cv2.INTER_LINEAR)
    gain = np.power(TARGET / (illum + 1e-3), ALPHA)
    gain = np.clip(gain, GAIN_MIN, GAIN_MAX)
    out = np.clip(lf * gain, 0.0, 1.0)
    return (out * 255.0 + 0.5).astype(np.uint8)


def enhance(bgr):
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    rgb = gray_world(rgb)
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2Lab)
    l, a, b = cv2.split(lab)
    l = local_tone_map(l)
    l = cv2.createCLAHE(clipLimit=CLAHE_CLIP, tileGridSize=(8, 8)).apply(l)
    rgb = cv2.cvtColor(cv2.merge([l, a, b]), cv2.COLOR_Lab2RGB)
    rgb = boost_saturation(rgb, SAT)
    blur = cv2.GaussianBlur(rgb, (0, 0), 2.0)
    rgb = cv2.addWeighted(rgb, 1.0 + SHARP, blur, -SHARP, 0)
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)


def stats(name, bgr):
    g = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    print(f"{name}: mean={g.mean():.1f} std={g.std():.1f} "
          f"burnt(>=250)={100*(g>=250).mean():.1f}% dark(<=30)={100*(g<=30).mean():.1f}%")


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    img = cv2.imread(src)
    out = enhance(img)
    stats("before", img)
    stats("after ", out)
    side = np.hstack([img, out])
    cv2.imwrite(dst, side, [cv2.IMWRITE_JPEG_QUALITY, 90])
