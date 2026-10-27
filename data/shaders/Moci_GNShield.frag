#version 110

uniform sampler2D shieldTex;
uniform sampler2D fxTex;
uniform float type;
uniform vec4 state;
uniform vec3 gnColor;
uniform float fluxLevel;

varying vec2 fragUV;

vec2 recoverShieldUV(float size,vec2 uv,vec2 rel){
    return ((rel*256.0+(uv-0.5)*2.0*size)/256.0);
}

void main() {
    vec4 col = texture2D(shieldTex,fragUV);
    float alpha = 0.0;
    vec3 finalColor = vec3(0.2, 0.4, 1.0);
    
    /* type =>mode: 0 for normal band spread | 1 for hit | 2 for nothing-> stencil func */
    if(type<=0.5){
        col.w = 0.5;   
        float life = state.x;
        float actualSize = state.y;
        float effectiveSize = state.z; 
        float combinedBaseAlpha = state.w;
        
        float dist = length((fragUV - 0.5) * 2.0)*256.0;
        float bandSize = effectiveSize/10.0*((life<0.1)?life*10.0:1.0);
        
        /* GN护盾双向扩散：0-0.5向外，0.5-1.0向内收缩 */
        float effectiveLife = life;
        float brightness = 1.0;
        if (life <= 0.5) {
            /* 前半周期：向外扩散 */
            effectiveLife = life * 2.0;
        } else {
            /* 后半周期：向内收缩，增强亮度 */
            effectiveLife = 2.0 - (life * 2.0);
            brightness = 1.3; /* 收缩时更亮 */
        }
        
        float startDist = effectiveLife*actualSize;
        alpha = sin(3.1415926*(dist-startDist)/bandSize);
        if(dist<startDist||dist>startDist+bandSize){
            alpha = 0.0;
        }
        alpha = alpha*combinedBaseAlpha*brightness;
        if(alpha>1.0) alpha = 1.0;
        
        /* GN护盾颜色系统：低幅能蓝色，高幅能红色 */
        vec3 lowFluxColor = gnColor;  /* 使用传入的基础颜色 */
        vec3 highFluxColor = vec3(1.0, 0.3, 0.2); /* 红色 */
        finalColor = mix(lowFluxColor, highFluxColor, clamp(fluxLevel, 0.0, 1.0));
        
        /* 在收缩阶段增加白色光芒 */
        if (life > 0.5) {
            finalColor = mix(finalColor, vec3(1.0, 1.0, 1.0), 0.2);
        }
        
    }else if(type<= 2.5){
        float life = state.x;
        float size = state.y; /* hit size */
        vec2 relUV = state.zw; /* shieldTex uv for hitpoint */
        float combinedShieldAlphaBase = type-1.0;
        vec2 sampleUV = recoverShieldUV(size*0.5,fragUV,relUV);
        col = texture2D(shieldTex,sampleUV);
        vec4 col2 = texture2D(fxTex,fragUV);
        col = col*col2;
        float dist = length(fragUV - 0.5)*size;
        alpha = sin(3.1415926*(dist-0.5*life*size)/0.5*life*size);
        if(dist>size){
            alpha = 0.0;
        }
        if(dist<=0.5*life*size){
            alpha = 1.0;
        }
        alpha=alpha*combinedShieldAlphaBase;
        if(alpha>1.0) alpha = 1.0;
        
        /* 击中效果也使用幅能相关颜色，但更亮 */
        vec3 lowFluxColor = gnColor * 1.5;  /* 基于传入颜色的亮版本 */
        vec3 highFluxColor = vec3(1.0, 0.5, 0.3); /* 亮红色 */
        finalColor = mix(lowFluxColor, highFluxColor, clamp(fluxLevel, 0.0, 1.0));
        
    }else{
        alpha = 1.0;
        finalColor = gnColor + vec3(fluxLevel * 0.0001); /* 确保uniform被使用 */
    }
    
    gl_FragColor = vec4(finalColor * col.xyz, col.w*alpha);
}
