import { useEffect, useRef } from 'react'

type Point = {
  x: number
  y: number
  vx: number
  vy: number
  radius: number
}

export function AuthParticles() {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const pointer = { x: window.innerWidth / 2, y: window.innerHeight / 2 }
    const points: Point[] = []
    let frame = 0
    let tick = 0

    const resize = () => {
      const ratio = window.devicePixelRatio || 1
      canvas.width = Math.floor(window.innerWidth * ratio)
      canvas.height = Math.floor(window.innerHeight * ratio)
      canvas.style.width = `${window.innerWidth}px`
      canvas.style.height = `${window.innerHeight}px`
      ctx.setTransform(ratio, 0, 0, ratio, 0, 0)

      points.length = 0
      const amount = Math.min(132, Math.floor((window.innerWidth * window.innerHeight) / 13500))
      for (let i = 0; i < amount; i++) {
        points.push({
          x: Math.random() * window.innerWidth,
          y: Math.random() * window.innerHeight,
          vx: (Math.random() - 0.5) * 0.22,
          vy: (Math.random() - 0.5) * 0.22,
          radius: 1.1 + Math.random() * 1.9,
        })
      }
    }

    const onPointerMove = (event: PointerEvent) => {
      pointer.x = event.clientX
      pointer.y = event.clientY
    }

    const drawRibbon = (width: number, height: number) => {
      for (let line = 0; line < 6; line++) {
        ctx.beginPath()
        const baseY = height * (0.1 + line * 0.16)
        for (let x = -80; x <= width + 80; x += 24) {
          const wave = Math.sin((x + tick * (0.7 + line * 0.15)) / 150 + line) * 22
          const y = baseY + wave + Math.cos((x - tick * 0.35) / 260) * 16
          if (x === -80) ctx.moveTo(x, y)
          else ctx.lineTo(x, y)
        }
        ctx.strokeStyle = line % 2 === 1 ? 'rgba(79,115,232,0.11)' : 'rgba(80,180,190,0.085)'
        ctx.lineWidth = 1.2
        ctx.stroke()
      }
    }

    const draw = () => {
      const width = window.innerWidth
      const height = window.innerHeight
      tick += 1

      ctx.clearRect(0, 0, width, height)

      const glow = ctx.createRadialGradient(pointer.x, pointer.y, 0, pointer.x, pointer.y, 360)
      glow.addColorStop(0, 'rgba(79,115,232,0.12)')
      glow.addColorStop(0.42, 'rgba(84,190,205,0.07)')
      glow.addColorStop(1, 'rgba(255,255,255,0)')
      ctx.fillStyle = glow
      ctx.fillRect(0, 0, width, height)

      drawRibbon(width, height)

      for (const point of points) {
        const dx = pointer.x - point.x
        const dy = pointer.y - point.y
        const distance = Math.sqrt(dx * dx + dy * dy)

        if (distance < 170) {
          const pull = (170 - distance) / 170
          point.vx += (dx / Math.max(distance, 1)) * pull * 0.006
          point.vy += (dy / Math.max(distance, 1)) * pull * 0.006
        }

        point.x += point.vx
        point.y += point.vy
        point.vx *= 0.996
        point.vy *= 0.996

        if (point.x < -20) point.x = width + 20
        if (point.x > width + 20) point.x = -20
        if (point.y < -20) point.y = height + 20
        if (point.y > height + 20) point.y = -20

        ctx.beginPath()
        ctx.arc(point.x, point.y, point.radius, 0, Math.PI * 2)
        ctx.fillStyle = 'rgba(79,115,232,0.24)'
        ctx.fill()
      }

      for (let i = 0; i < points.length; i++) {
        for (let j = i + 1; j < points.length; j++) {
          const a = points[i]
          const b = points[j]
          const dx = a.x - b.x
          const dy = a.y - b.y
          const distance = Math.sqrt(dx * dx + dy * dy)
          if (distance > 145) continue
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.strokeStyle = `rgba(79,115,232,${0.16 * (1 - distance / 145)})`
          ctx.lineWidth = 1
          ctx.stroke()
        }
      }

      frame = requestAnimationFrame(draw)
    }

    resize()
    draw()
    window.addEventListener('resize', resize)
    window.addEventListener('pointermove', onPointerMove)

    return () => {
      cancelAnimationFrame(frame)
      window.removeEventListener('resize', resize)
      window.removeEventListener('pointermove', onPointerMove)
    }
  }, [])

  return <canvas ref={canvasRef} className="pointer-events-none absolute inset-0 h-full w-full" aria-hidden="true" />
}
