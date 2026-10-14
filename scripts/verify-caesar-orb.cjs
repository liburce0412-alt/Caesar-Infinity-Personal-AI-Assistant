// Render the production GLES2 sources offscreen. This is shader QA, not Android FPS evidence.
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '..');
const source = fs.readFileSync(path.join(root, 'apps/android/app/src/main/java/com/campusai/core/designsystem/SpectraRenderer.kt'), 'utf8');
const shader = name => source.match(new RegExp(`const val ${name} = """([\\s\\S]*?)"""`))[1];
const orb = fs.readFileSync(path.join(root, 'apps/android/app/src/main/java/com/campusai/core/designsystem/CaesarOrbShader.kt'), 'utf8').split('"""')[1];
const output = path.resolve(process.argv[2] || path.join(root, 'artifacts/caesar-orb-20261013'));
fs.mkdirSync(output, { recursive: true });
(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true });
  try {
    const page = await browser.newPage({ viewport: { width: 1200, height: 760 }, deviceScaleFactor: 1 });
    await page.setContent('<style>body{margin:0;background:#ececf0;font:15px system-ui}main{display:grid;grid-template-columns:repeat(4,300px)}section{position:relative;height:380px}canvas{width:300px;height:380px}span{position:absolute;top:22px;left:22px;color:#60646f}.dark span{color:#cbd1df}</style><main></main>');
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    const info = await page.evaluate(({ vertex, scene, blit, orb }) => {
      const frames = [];
      const names = ['静待 · 大气泡', '聆听 · RMS 示例值', '生成 · 紧凑状态', '首页 · 48dp'];
      for (let dark = 0; dark < 2; dark++) for (let mode = 0; mode < 4; mode++) {
        const section = document.createElement('section'); section.className = dark ? 'dark' : '';
        section.innerHTML = `<canvas width="600" height="760"></canvas><span>${names[mode]} / ${dark ? '深色' : '浅色'}</span>`;
        document.querySelector('main').append(section);
        const gl = section.querySelector('canvas').getContext('webgl', { preserveDrawingBuffer: true, alpha: false });
        if (!gl) throw Error('WebGL unavailable');
        function compile(type, text) { const s = gl.createShader(type); gl.shaderSource(s, text); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(s)); return s; }
        function program(fragment) { const p = gl.createProgram(); gl.attachShader(p, compile(gl.VERTEX_SHADER, vertex)); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fragment)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; }
        const field = program(scene), copy = program(blit), bubble = program(orb);
        const buffer = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buffer); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
        const texture = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 300, 380, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
        for (const [key,val] of [[gl.TEXTURE_MIN_FILTER,gl.LINEAR],[gl.TEXTURE_MAG_FILTER,gl.LINEAR],[gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE],[gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D,key,val);
        const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,texture,0);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('FBO incomplete');
        function use(p) { gl.useProgram(p); const a = gl.getAttribLocation(p,'aPosition'); gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a,2,gl.FLOAT,false,0,0); }
        function f(p,n,v) { gl.uniform1f(gl.getUniformLocation(p,n),v); }
        function draw(t, loudness) {
          gl.disable(gl.BLEND); gl.bindTexture(gl.TEXTURE_2D,null); gl.bindFramebuffer(gl.FRAMEBUFFER,fbo); gl.viewport(0,0,300,380); use(field);
          gl.uniform2f(gl.getUniformLocation(field,'uResolution'),600,760); gl.uniform2f(gl.getUniformLocation(field,'uPointer'),.62,.45);
          for (const [n,v] of Object.entries({uTime:t,uRailTime:t,uEnvironment:0,uPreviousEnvironment:0,uEnvironmentMix:1,uQuality:2,uDark:dark,uPhase:0,uVisualStyle:1})) f(field,n,v);
          gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
          gl.bindFramebuffer(gl.FRAMEBUFFER,null); gl.viewport(0,0,600,760); gl.bindTexture(gl.TEXTURE_2D,texture); use(copy); gl.uniform1i(gl.getUniformLocation(copy,'uScene'),0); gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
          const size = [408,220,96,112][mode], x = (600-size)/2, y = (760-size)/2;
          gl.enable(gl.SCISSOR_TEST); gl.scissor(x,y,size,size); gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA,gl.ONE_MINUS_SRC_ALPHA); use(bubble);
          gl.uniform1i(gl.getUniformLocation(bubble,'uScene'),0); gl.uniform2f(gl.getUniformLocation(bubble,'uSurfaceSize'),600,760); gl.uniform4f(gl.getUniformLocation(bubble,'uRegion'),x/600,y/760,size/600,size/760);
          for (const [n,v] of Object.entries({uTime:t,uDark:dark,uEnergy:mode===1?loudness:0,uActivity:mode===2?.8:0,uListening:mode===1?1:0})) f(bubble,n,v);
          gl.drawArrays(gl.TRIANGLE_STRIP,0,4); gl.disable(gl.SCISSOR_TEST);
          if(gl.getError()!==gl.NO_ERROR) throw Error('GL draw error');
        }
        frames.push(draw); draw(1.7,.58);
      }
      window.renderOrbFrames = (t, rms) => frames.forEach(draw => draw(t,rms));
      return { contexts: frames.length, shadersCompiled: true, fboComplete: true };
    }, { vertex: shader('VERTEX_SHADER'), scene: shader('SCENE_FRAGMENT_SHADER'), blit: shader('BLIT_FRAGMENT_SHADER'), orb });
    await page.screenshot({ path: path.join(output, 'shader-contact.png') });
    for (const [label, time, rms] of [['quiet',1.7,0],['voice-peak',2.9,1],['settled',5.2,.08]]) {
      await page.evaluate(([t,r]) => window.renderOrbFrames(t,r), [time,rms]);
      await page.screenshot({ path: path.join(output, `shader-${label}.png`) });
    }
    // Exercise 240 consecutive timeline samples, including rapid audio changes, on exact shaders.
    await page.evaluate(() => { for(let i=0;i<240;i++) window.renderOrbFrames(i/120, i%40<20?.9:0); });
    fs.writeFileSync(path.join(output,'shader-validation.json'), JSON.stringify({ ...info, timelineSamples:240, errors, note:'Desktop WebGL visual/compile check; not device frame rate.' }, null, 2));
    if (errors.length) throw Error(errors.join('\n'));
    console.log(JSON.stringify(info));
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
