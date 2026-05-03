#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 Android 应用图标脚本
自动处理新图标并生成所有需要的尺寸
"""

import os
import sys
import requests

try:
    from PIL import Image
except ImportError:
    print("安装 Pillow...")
    os.system("pip install pillow")
    from PIL import Image

# Android 图标密度配置
DENSITY_SIZES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

def download_image(url, save_path):
    """从URL下载图片"""
    try:
        response = requests.get(url, timeout=30)
        response.raise_for_status()
        with open(save_path, 'wb') as f:
            f.write(response.content)
        return True
    except Exception as e:
        print(f"下载失败: {e}")
        return False

def generate_icons(source_path, project_root):
    """生成所有需要尺寸的图标"""
    
    if not os.path.exists(source_path):
        print(f"❌ 找不到源图片: {source_path}")
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

def main():
    project_root = os.path.dirname(os.path.abspath(__file__))
    source_icon = os.path.join(project_root, "new_icon.png")
    
    # 检查是否存在图片
    if not os.path.exists(source_icon):
        # 尝试从用户提供的图片URL下载
        print("提示: 请把图标保存为 new_icon.png 到项目根目录")
        print("或者直接运行: python make_icons.py <图片路径>")
        return
    
    # 生成图标
    success = generate_icons(source_icon, project_root)
    
    if success:
        print("\n下一步:")
        print("1. 提交图标: git add app/src/main/res/mipmap-*")
        print("2. 提交并推送: git commit -m 'feat: 更新应用图标'")
        print("3. 发布版本: git tag -a v2.1.1 -m 'v2.1.1: 更新应用图标'")
    
    return 0 if success else 1

if __name__ == "__main__":
    sys.exit(main())
