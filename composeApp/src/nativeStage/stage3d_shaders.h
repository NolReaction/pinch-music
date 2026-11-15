// ES 2.0 shaders, also used by the offscreen reference renderer.
#pragma once
namespace cascade {
static const char* sceneVS = R"GL(
attribute highp vec3 aPos;
attribute mediump vec3 aNormal;
attribute mediump vec2 aMat;
uniform highp vec3 uOffset;
uniform highp vec2 uTilt; // cosine and sine of the playing face's angle in X/Y
uniform highp vec4 uLens; // projection multipliers, near, far
uniform mediump vec4 uKey; // impact, style, preview target, last crossed LED
varying mediump vec3 vPos;
varying mediump vec3 vN;
varying mediump vec2 vMat;
const vec3 eye=vec3(2.80,-4.88,8.20);
const vec3 forward=vec3(-.279687,.487454,-.827155);
const vec3 right=vec3(.947306,0.,-.32033);
const vec3 up=vec3(-.156135,-.873148,-.461818);
void main(){
    mat2 turn=mat2(uTilt.x,uTilt.y,-uTilt.y,uTilt.x);
    vPos=vec3(turn*aPos.xy,aPos.z)+uOffset;
    vec3 d=vPos-eye;
    float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)*uLens.x,dot(d,up)*uLens.y,
        (uLens.w+uLens.z)/(uLens.w-uLens.z)*z-2.*uLens.w*uLens.z/(uLens.w-uLens.z),z);
    vN=vec3(turn*aNormal.xy,aNormal.z);
    vMat=aMat;
}
)GL";

static const char* sceneFS = R"GL(
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying mediump vec3 vPos;
varying mediump vec3 vN;
varying mediump vec2 vMat;
uniform mediump vec4 uKey;
uniform mediump vec4 uBall;
uniform mediump vec2 uSpin; // cos, sin
const vec3 eye=vec3(2.80,-4.88,8.20);
const vec3 L=vec3(-.538,-.715,.447);
vec3 tint(float style){
    if(style>1.5) return vec3(.18,.96,.88);
    if(style>.5) return vec3(1.,.66,.105);
    return vec3(.96,.235,.875);
}
float strip(float x,float halfWidth,float blur){
    return 1.-smoothstep(halfWidth,halfWidth+blur,abs(x));
}
// Analytic studio cards in reflected direction space: a broad ceiling box,
// a narrow white strip at camera-left and a lavender kicker at camera-right.
vec3 studio(vec3 r){
    float roof=smoothstep(.00,.68,-r.y);
    vec3 c=mix(vec3(.070,.085,.125),vec3(.43,.49,.63),roof);
    float window=strip(r.x+.53,.13,.12)*strip(r.y+.43,.31,.11)*smoothstep(-.1,.6,r.z);
    float bar=strip(r.y+.67+.22*r.x,.024,.045)*smoothstep(-.30,.34,r.z);
    float violet=strip(r.x-.68,.15,.14)*strip(r.y+.13,.34,.15)*smoothstep(-.1,.45,r.z);
    return c+vec3(.61,.67,.78)*window+vec3(.83,.82,.76)*bar+
           vec3(.18,.09,.29)*violet;
}
void main(){
    vec3 n=normalize(vN), v=normalize(eye-vPos), r=reflect(-v,n);
    float mat=vMat.x;
    if(mat>8.5) {
        // Polished ruby marble. The inclusion rotates with the ball inside
        // the clear glass skin; the studio reflections remain in world space.
        vec3 bn=vec3(n.x*uSpin.x+n.y*uSpin.y,-n.x*uSpin.y+n.y*uSpin.x,n.z);
        vec3 refr=refract(-v,n,.67);
        float fres=pow(1.-max(0.,dot(n,v)),4.);
        float ribbon=bn.y+.40*bn.x+.23*sin(3.85*bn.x+2.17*bn.z);
        float lightBand=strip(ribbon-.03,.12,.13);
        float redBand=smoothstep(.07,.23,ribbon)*(.55+.45*bn.z);
        vec3 body=mix(vec3(.105,.018,.085),vec3(.43,.037,.115),.5+.5*bn.z);
        body=mix(body,vec3(.69,.12,.095),redBand);
        vec3 inner=studio(reflect(refr,normalize(vec3(bn.x*.50,-.80,bn.z*.49))));
        body=mix(body,vec3(.23,.22,.32)+inner*.43,lightBand*.85);
        float baseGlint=strip(ribbon+.13,.016,.041)*(.55+.45*r.z);
        vec3 color=body*(.69+.25*max(0.,dot(n,L)))+vec3(.38,.16,.16)*baseGlint;
        vec3 env=studio(r);
        color+=env*(.39+.64*fres);
        color+=vec3(.93,.64,.70)*pow(max(dot(reflect(-L,n),v),0.),94.);
        color+=vec3(.20,.02,.13)*uBall.w*max(n.y,0.);
        gl_FragColor=vec4(color,1.); return;
    }
    vec3 env=studio(r);
    float nl=max(0.,dot(n,L)), nv=max(0.,dot(n,v));
    float edge=pow(1.-nv,3.);
    float spec=pow(max(0.,dot(reflect(-L,n),v)),54.);
    vec3 accent=tint(uKey.y);
    float impact=uKey.x,preview=uKey.z;
    vec3 col;
    if(mat<1.5) {
        // Black lacquer over a dark resin core. The playing surface illuminates
        // across its full area at contact, including the rounded leading edge.
        float top=1.-step(.5,mat);
        float ao=1.-.43*exp(-14.*dot(vPos.xz-uBall.xz,vPos.xz-uBall.xz))*
                     (1.-smoothstep(.24,.83,abs(vPos.y-uBall.y)));
        col=(vec3(.022,.032,.061)+vec3(.019,.021,.040)*nl+
             env*(.105+.23*edge)+vec3(.23,.22,.27)*spec)*ao;
        float emissive=abs(uKey.y-1.)<.25 ? (.075+.075*edge) : (.78+.21*top);
        col=mix(col,accent*(.40+.48*top+.14*nl)+env*.075,impact*emissive);
    } else if(mat<2.5) {
        col=mix(vec3(.085,.096,.128)+env*.16,accent,
                min(1.,impact*1.25+preview*.97));
    } else if(mat<3.5 || (mat>4.5 && mat<5.5) || (mat>6.5 && mat<7.5)) {
        float polished=mat<3.5 ? .75 : (mat<5.5 ? 1.07 : .57);
        col=vec3(.042,.060,.088)+min(env,vec3(.78))*polished+vec3(.11,.13,.16)*spec;
        if(mat>6.5) col=mix(col,vec3(.145,.177,.226),.30);
        col+=accent*.12*impact;
    } else if(mat<4.5) {
        col=vec3(.025,.032,.046)+env*.23;
    } else if(mat<6.5) {
        col=mix(vec3(.20,.135,.052),accent,min(1.,preview*.84+impact+.28));
    } else {
        float visited=step(vMat.y-.1,uKey.w);
        float latest=1.-step(.25,abs(uKey.w-vMat.y));
        col=mix(vec3(.11,.20,.24),vec3(.23,.84,.95),.66*visited);
        col+=vec3(.36,.22,.34)*latest;
    }
    gl_FragColor=vec4(col,1.);
}
)GL";

// Draw the softly lit wall and translucent projected shadows before the solid
// geometry. One reusable bank carries all visible shadows in a single draw.
static const char* wallVS = R"GL(
attribute highp vec3 aPos;
attribute mediump vec3 aNormal;
attribute mediump vec2 aMat;
uniform highp vec4 uLens;
uniform highp vec4 uDecals[16]; // x, y, type (0 key, 1 ball), face slope
uniform mediump vec4 uWallLight;
varying lowp vec4 vColor;
const vec3 eye=vec3(2.80,-4.88,8.20);
const vec3 forward=vec3(-.279687,.487454,-.827155);
const vec3 right=vec3(.947306,0.,-.32033);
const vec3 up=vec3(-.156135,-.873148,-.461818);
void main(){
    if(aMat.x<.5) {
        vec2 uv=aPos.xy*.5+.5;
        vec3 col=mix(vec3(.235,.310,.424),vec3(.413,.415,.535),uv.x*.64+uv.y*.36);
        vec2 pool=uv-vec2(.69,.66);
        col+=vec3(.043,.028,.064)*(1.-smoothstep(.0,.67,dot(pool,pool)));
        col-=vec3(.025,.032,.04)*dot(aPos.xy,aPos.xy);
        vec2 d=(aPos.xy-uWallLight.xy)*vec2(uWallLight.w,1.);
        col+=vec3(.070,.017,.046)*uWallLight.z*(1.-smoothstep(.0,.48,dot(d,d)));
        gl_Position=vec4(aPos.xy,0.,1.); vColor=vec4(col,1.); return;
    }
    vec4 data=uDecals[int(aMat.y+.5)];
    float ball=data.z;
    vec2 p=data.xy+mix(vec2(.08,.19),vec2(.13,.23),ball);
    p.x+=aPos.x*mix(.75,.38,ball)+aPos.y*mix(.26,.04,ball);
    p.y+=aPos.y*mix(.69,.38,ball)+aPos.x*.75*data.w;
    vec3 d=vec3(p,-1.347)-eye;
    float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)*uLens.x,dot(d,up)*uLens.y,0.,z);
    vColor=vec4(.054,.072,.122,aNormal.x*mix(.23,.16,ball));
}
)GL";
static const char* wallFS = R"GL(
precision mediump float;
varying lowp vec4 vColor;
void main(){gl_FragColor=vColor;}
)GL";
} // namespace cascade
