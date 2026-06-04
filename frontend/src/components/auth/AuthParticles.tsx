/**
 * AuthParticles 组件 —— 认证页面的粒子动画背景
 *
 * 【用途与使用场景】
 * 在登录、注册、忘记密码页面背后提供视觉装饰效果。
 * 使用 Canvas API 绘制漂浮粒子、粒子间连线、波浪飘带和鼠标跟随光晕。
 *
 * 【视觉效果说明】
 * 1. 粒子系统：在画布上生成随机分布的粒子，粒子之间距离较近时会绘制连线。
 * 2. 鼠标交互：鼠标移动时在指针位置产生径向光晕，附近的粒子会被轻微吸引。
 * 3. 飘带动画：绘制 6 条波浪状半透明飘带，随时间缓慢波动。
 * 4. 自适应屏幕：窗口大小改变时重新计算画布尺寸和粒子数量。
 *
 * 【性能优化】
 * - 使用 devicePixelRatio 适配高分屏，保证清晰度。
 * - 粒子数量根据屏幕面积动态计算，最大 132 个。
 * - 粒子速度通过摩擦系数 0.996 逐渐衰减，避免无限加速。
 */

import { useEffect, useRef } from 'react'

/**
 * Point 类型 —— 表示单个粒子的状态
 * @property x - 当前横坐标
 * @property y - 当前纵坐标
 * @property vx - 横向速度
 * @property vy - 纵向速度
 * @property radius - 粒子半径（像素）
 */
type Point = {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
}

export function AuthParticles() {
  // 用于获取 canvas DOM 元素的引用
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    // 鼠标/触摸指针位置，初始设为屏幕中心
    const pointer = { x: window.innerWidth / 2, y: window.innerHeight / 2 }
    // 所有粒子的集合
    const points: Point[] = []
    // 当前动画帧 ID，用于取消动画
    let frame = 0
    // 时间计数器，用于驱动飘带的周期性运动
    let tick = 0

    /**
     * resize 处理函数：
     * 1. 根据 devicePixelRatio 调整画布实际像素尺寸（适配 Retina 屏）
     * 2. 根据屏幕面积计算粒子数量
     * 3. 重新生成随机分布的粒子
     */
    const resize = () => {
      const ratio = window.devicePixelRatio || 1
      canvas.width = Math.floor(window.innerWidth * ratio)
      canvas.height = Math.floor(window.innerHeight * ratio)
      canvas.style.width = `${window.innerWidth}px`
      canvas.style.height = `${window.innerHeight}px`
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0)

      // 清空旧粒子，根据屏幕面积生成新粒子（最大 132 个）
      points.length = 0
      const amount = Math.min(132, Math.floor((window.innerWidth * window.innerHeight) / 13500))
      for (let i = 0; i < amount; i++) {
        points.push({
          x: Math.random() * window.innerWidth,
          y: Math.random() * window.innerHeight,
          vx: (Math.random() - 0.5) * 0.22, // 随机初始速度（-0.11 ~ 0.11）
          vy: (Math.random() - 0.5) * 0.22,
          radius: 1.1 + Math.random() * 1.9, // 半径 1.1 ~ 3.0 像素
        })
      }
    }

    // 跟踪鼠标/触摸位置
    const onPointerMove = (event: PointerEvent) => {
      pointer.x = event.clientX
      pointer.y = event.clientY
    }

    /**
     * 绘制飘带（波浪线条装饰）
     * @param width - 画布宽度
     * @param height - 画布高度
     *
     * 共绘制 6 条水平方向的波浪线条，交替使用蓝色和青色，
     * 通过 sin 和 cos 函数组合产生柔和的波动效果。
     */
    const drawRibbon = (width: number, height: number) => {
      for (let line = 0; line < 6; line++) {
        ctx.beginPath()
        // 每条飘带在垂直方向上的基准 Y 坐标
        const baseY = height * (0.1 + line * 0.16)
        for (let x = -80; x <= width + 80; x += 24) {
          // 用 sin + cos 组合产生自然波动
          const wave = Math.sin((x + tick * (0.7 + line * 0.15)) / 150 + line) * 22
          const y = baseY + wave + Math.cos((x - tick * 0.35) / 260) * 16
          if (x === -80) ctx.moveTo(x, y)
          else ctx.lineTo(x, y)
        }
        // 奇偶行使用不同颜色
        ctx.strokeStyle = line % 2 === 1 ? 'rgba(79,115,232,0.11)' : 'rgba(80,180,190,0.085)'
        ctx.lineWidth = 1.2
        ctx.stroke()
      }
    }

    /**
     * 主绘制函数，每帧调用一次，负责：
     * 1. 清除画布
     * 2. 在鼠标位置绘制径向光晕
     * 3. 绘制飘带
     * 4. 更新并绘制所有粒子（含鼠标吸引效果）
     * 5. 绘制粒子之间的连线
     */
    const draw = () => {
      const width = window.innerWidth
      const height = window.innerHeight
      tick += 1 // 递增时间计数器

      // 清除上一帧内容
      ctx.clearRect(0, 0, width, height)

      // 在鼠标位置绘制径向渐变光晕
      const glow = ctx.createRadialGradient(pointer.x, pointer.y, 0, pointer.x, pointer.y, 360)
      glow.addColorStop(0, 'rgba(79,115,232,0.12)')    // 中心：蓝色
      glow.addColorStop(0.42, 'rgba(84,190,205,0.07)')  // 中间：青色
      glow.addColorStop(1, 'rgba(255,255,255,0)')        // 边缘：透明
      ctx.fillStyle = glow
      ctx.fillRect(0, 0, width, height)

      // 绘制装饰飘带
      drawRibbon(width, height)

      // 更新每个粒子的位置和速度
      for (const point of points) {
        const dx = pointer.x - point.x
        const dy = pointer.y - point.y
        const distance = Math.sqrt(dx * dx + dy * dy)

        // 粒子在鼠标 170px 范围内时会受到轻微吸引力
        if (distance < 170) {
          const pull = (170 - distance) / 170 // 越近吸引力越大
          point.vx += (dx / Math.max(distance, 1)) * pull * 0.006
          point.vy += (dy / Math.max(distance, 1)) * pull * 0.006
        }

        // 根据速度移动粒子
        point.x += point.vx
        point.y += point.vy
        // 摩擦力衰减，避免粒子无限加速
        point.vx *= 0.996
        point.vy *= 0.996

        // 边界环绕：粒子移出屏幕后从另一侧出现
        if (point.x < -20) point.x = width + 20
        if (point.x > width + 20) point.x = -20
        if (point.y < -20) point.y = height + 20
        if (point.y > height + 20) point.y = -20

        // 绘制粒子圆点
        ctx.beginPath()
        ctx.arc(point.x, point.y, point.radius, 0, Math.PI * 2)
        ctx.fillStyle = 'rgba(79,115,232,0.24)'
        ctx.fill()
      }

      // 绘制粒子之间的连线（距离 < 145px 的粒子对之间）
      for (let i = 0; i < points.length; i++) {
        for (let j = i + 1; j < points.length; j++) {
          const a = points[i]
          const b = points[j]
          const dx = a.x - b.x
          const dy = a.y - b.y
          const distance = Math.sqrt(dx * dx + dy * dy)
          if (distance > 145) continue // 距离太远则跳过
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          // 线条透明度随距离增大而减小
          ctx.strokeStyle = `rgba(79,115,232,${0.16 * (1 - distance / 145)})`
          ctx.lineWidth = 1
          ctx.stroke()
        }
      }

      // 请求下一帧动画
      frame = requestAnimationFrame(draw)
    }

    // 初始化：设置画布尺寸、启动动画循环、绑定事件监听
    resize()
    draw()
    window.addEventListener('resize', resize)
    window.addEventListener('pointermove', onPointerMove)

    // 清理函数：组件卸载时停止动画并移除事件监听
    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onPointerMove)
    }
  }, []) // 空依赖数组，仅在组件挂载/卸载时执行

  // Canvas 元素覆盖整个父容器，pointer-events-none 让鼠标事件穿透到下方表单
  return <canvas ref={canvasRef} className="pointer-events-none absolute inset-0 h-full w-full" aria-hidden="true" />
}
