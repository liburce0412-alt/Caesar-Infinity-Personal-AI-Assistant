import { expect, test } from '@playwright/test'
import { readFile } from 'node:fs/promises'

// Exercise the exact Android ES2 shaders on a local WebGL driver, not a reimplementation.
// This verifies shader compatibility and scene sampling, not Android device performance.
test('native liquid glass shaders compile and refract the shared scene', async ({ page }) => {
  const source = await readFile(new URL('../../android/app/src/main/java/com/campusai/core/designsystem/SpectraRenderer.kt', import.meta.url), 'utf8')
  const shader = (name: string) => {
    const match = source.match(new RegExp(`const val ${name} = """([\\s\\S]*?)"""`))
    if (!match) throw new Error(`Missing ${name}`)
    return match[1]
  }
  const result = await page.evaluate(({ vertex, optical, scene }) => {
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = 128
    document.body.append(canvas)
    const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true })!
    if (!gl) throw new Error('WebGL unavailable')
    const compile = (type: number, text: string) => {
      const value = gl.createShader(type)!
      gl.shaderSource(value, text); gl.compileShader(value)
      if (!gl.getShaderParameter(value, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(value)!)
      return value
    }
    const link = (fragment: string) => {
      const program = gl.createProgram()!
      gl.attachShader(program, compile(gl.VERTEX_SHADER, vertex))
      gl.attachShader(program, compile(gl.FRAGMENT_SHADER, fragment))
      gl.linkProgram(program)
      if (!gl.getProgramParameter(program, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(program)!)
      return program
    }
    link(scene)
    const program = link(optical)
    gl.useProgram(program)
    const buffer = gl.createBuffer()
    gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW)
    const position = gl.getAttribLocation(program, 'aPosition')
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0)
    const texture = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, texture)
    const pixels = new Uint8Array(128 * 128 * 4)
    for (let y=0; y<128; y++) for (let x=0; x<128; x++) {
      const i=(y*128+x)*4
      pixels[i]=x*2; pixels[i+1]=y*2; pixels[i+2]=((x>>3)+(y>>3))%2*255; pixels[i+3]=255
    }
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 128, 128, 0, gl.RGBA, gl.UNSIGNED_BYTE, pixels)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
    const loc = (name: string) => gl.getUniformLocation(program, name)
    gl.uniform2f(loc('uSurfaceSize'),128,128)
    gl.uniform4f(loc('uRegion'),0,0,1,1)
    gl.uniform2f(loc('uRegionSize'),128,128)
    gl.uniform2f(loc('uTouch'),.5,.5)
    for (const [name,value] of Object.entries({uCornerRadius:32,uRefractionPx:6,uDispersionPx:.65,uFlowPx:1.4,uBodyOpacity:.095,uDark:0,uTime:10})) gl.uniform1f(loc(name),value)
    gl.viewport(0,0,128,128)
    gl.drawArrays(gl.TRIANGLE_STRIP,0,4)
    const output = new Uint8Array(pixels.length)
    gl.readPixels(0,0,128,128,gl.RGBA,gl.UNSIGNED_BYTE,output)
    let changed=0
    for (let i=0; i<pixels.length; i+=4) if (output[i+3] && Math.abs(output[i+2]-pixels[i+2])>25) changed++
    const renderTouch = (x: number, interaction: number) => {
      gl.uniform2f(loc('uTouch'),x,.6)
      gl.uniform1f(loc('uInteraction'),interaction)
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4)
      const frame = new Uint8Array(pixels.length)
      gl.readPixels(0,0,128,128,gl.RGBA,gl.UNSIGNED_BYTE,frame)
      return frame
    }
    const left = renderTouch(.2,1), right = renderTouch(.8,1)
    const restored = renderTouch(.8,0)
    let following = 0, residual = 0
    for (let i=0; i<pixels.length; i+=4) {
      if (Math.abs(left[i+2]-right[i+2])>25) following++
      if (Math.abs(restored[i+2]-output[i+2])>1) residual++
    }
    return { error: gl.getError(), changed, following, residual }
  }, { vertex: shader('VERTEX_SHADER'), optical: shader('OPTICAL_FRAGMENT_SHADER'), scene: shader('SCENE_FRAGMENT_SHADER') })
  expect(result.error).toBe(0)
  expect(result.changed).toBeGreaterThan(500)
  expect(result.following).toBeGreaterThan(500)
  expect(result.residual).toBe(0)
})
