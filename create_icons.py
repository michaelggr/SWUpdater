#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Android 应用图标脚本
请先把你的新图标保存为 new_icon.png 到项目根目录
"""

import os
import sys
try:
    from PIL import Image
except ImportError:
    print("请先安装 Pillow: pip install pillow")
    sys.exit(1)

# Android 图标密度对应的尺寸
DENSITY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

def generate_icons(source_path, project_root):
    """生成所有需要尺寸的图标"""
    
    if not os.path.exists(source_path):
        print(f"❌ 找不到源图片: {source_path}")
        print("请把你的新图标保存为 new_icon.png 到项目根目录")
        return False
    
    print(f"✅ 读取源图片: {source_path}")
    
    # 打开源图片
    img = Image.open(source_path).convert("RGBA")
    print(f"   原始尺寸: {img.size[0]}x{img.size[1]}")
    
    # 资源目录
    res_dir = os.path.join(project_root, "app", "src", "main", "res")
    
    print("\n🚀 生成图标:")
    
    for density, size in DENSITY_SIZES.items():
        density_dir = os.path.join(res_dir, density)
        if not os.path.exists(density_dir):
            os.makedirs(density_dir, exist_ok=True)
        
        # 调整图片尺寸 - 使用高质量缩放
        resized = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # 保存 ic_launcher.png
        out_normal = os.path.join(density_dir, "ic_launcher.png")
        resized.save(out_normal, "PNG")
        
        # 保存 ic_launcher_round.png
        out_round = os.path.join(density_dir, "ic_launcher_round.png")
        resized.save(out_round, "PNG")
        
        print(f"   ✓ {density}/ic_launcher.png ({size}x{size})")
    
    print("\n✨ 所有图标生成完成！")
    return True

if __name__ == "__main__":
    project_root = os.path.dirname(os.path.abspath(__file__))
    source_icon = os.path.join(project_root, "new_icon.png")
    
    success = generate_icons(source_icon, project_root)
    
    if success:
        print("\n下一步:")
        print("1. 提交图标: git add app/src/main/res/mipmap-*")
        print("2. 提交并推送: git commit -m 'feat: 更新应用图标'")
        print("3. 发布版本: git tag -a v2.1.1 -m 'v2.1.1: 更新应用图标'")
    sys.exit(0 if success else 1)
