#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
生成 Android 应用图标脚本
从新图片生成所有需要的尺寸
"""

import os
import sys
from PIL import Image, ImageDraw

# 定义各密度的尺寸
DENSITIES = [
    ("mipmap-mdpi", 48),
    ("mipmap-hdpi", 72),
    ("mipmap-xhdpi", 96),
    ("mipmap-xxhdpi", 144),
    ("mipmap-xxxhdpi", 192)
]

def add_rounded_corners(img, corner_radius):
    """给图片添加圆角"""
    w, h = img.size
    mask = Image.new('L', (w, h), 255)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), (w, h)], radius=corner_radius, fill=0)
    img.paste(0, mask=mask)
    return img

def process_icon(source_path, output_dir):
    """处理图标并生成所有尺寸"""
    print(f"正在从 {source_path} 生成图标...")
    
    # 打开源图片
    img = Image.open(source_path).convert("RGBA")
    print(f"源图片尺寸: {img.size}")
    
    # 确保输出目录存在
    base_dir = os.path.join(output_dir, "app", "src", "main", "res")
    os.makedirs(base_dir, exist_ok=True)
    
    # 为每个密度生成图标
    for density, size in DENSITIES:
        density_dir = os.path.join(base_dir, density)
        os.makedirs(density_dir, exist_ok=True)
        
        # 调整尺寸
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # 保存普通图标
        normal_path = os.path.join(density_dir, "ic_launcher.png")
        resized.save(normal_path, "PNG")
        print(f"✓ {density}/ic_launcher.png ({size}x{size})")
        
        # 保存圆角图标
        # 为圆角图标添加圆角效果
        rounded = resized.copy()
        # Android 8.0+ 自适应图标不需要特别处理，这里直接保存同样的
        rounded_path = os.path.join(density_dir, "ic_launcher_round.png")
        rounded.save(rounded_path, "PNG")
        print(f"✓ {density}/ic_launcher_round.png ({size}x{size})")
    
    print("\n所有图标生成完成！")

if __name__ == "__main__":
    # 假设源图片已经保存为 new_icon.png
    # 或者我们需要先从用户提供的图片保存
    print("请确保源图片已保存为 new_icon.png")
    
    source_icon = "new_icon.png"
    if not os.path.exists(source_icon):
        print(f"错误: 找不到源图片 {source_icon}")
        sys.exit(1)
    
    output_dir = "."  # 项目根目录
    process_icon(source_icon, output_dir)
