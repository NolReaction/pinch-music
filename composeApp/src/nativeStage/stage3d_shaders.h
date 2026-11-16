// ES 2.0 shaders, also used by the offscreen reference renderer.
#pragma once
namespace cascade {
#define STAGE_CAMERA_GLSL "const vec3 eye=vec3(2.80,-4.88,8.20);\n" \
    "const vec3 forward=vec3(-.279687,.487454,-.827155);\n" \
    "const vec3 right=vec3(.947306,0.,-.32033);\n" \
    "const vec3 up=vec3(-.156135,-.873148,-.461818);\n"

static const char* sceneVS = STAGE_CAMERA_GLSL R"GL(
attribute highp vec3 aPos;
attribute mediump vec3 aNormal;
attribute mediump vec2 aMat;
uniform highp vec3 uOffset;
uniform highp vec2 uTilt; // cosine and sine of the playing face's angle in X/Y
uniform highp vec4 uLens; // projection multipliers, near, far
uniform mediump vec4 uKey; // impact, signed judgement, preview target, cap travel
varying mediump vec3 vPos;
varying mediump vec3 vN;
varying mediump vec2 vMat;
varying mediump vec2 vFace;
void main(){
    mat2 turn=mat2(uTilt.x,uTilt.y,-uTilt.y,uTilt.x);
    vPos=vec3(turn*(aPos.xy+vec2(0.,uKey.w*aMat.y)),aPos.z)+uOffset;
    vec3 d=vPos-eye;
    float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)*uLens.x,dot(d,up)*uLens.y,
        (uLens.w+uLens.z)/(uLens.w-uLens.z)*z-2.*uLens.w*uLens.z/(uLens.w-uLens.z),z);
    vN=vec3(turn*aNormal.xy,aNormal.z);
    vMat=aMat;
    vFace=aPos.xz;
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
varying mediump vec2 vFace;
uniform mediump vec4 uKey;
uniform mediump vec4 uBall;
uniform mediump vec2 uSpin; // cos, sin
uniform lowp vec3 uPaint;
uniform lowp vec3 uAccent;
uniform sampler2D uGrain;
const vec3 eye=vec3(2.80,-4.88,8.20);
const vec3 L=vec3(.657,-.243,.714);
float strip(float x,float halfWidth,float blur){
    return 1.-smoothstep(halfWidth,halfWidth+blur,abs(x));
}
// Large softboxes, a dark flag and the lit plaster floor remain fixed in the
// reflection. A change of key pitch therefore changes the finish visibly.
vec3 studio(vec3 r){
    float ceiling=smoothstep(-.42,.73,-r.y);
    vec3 room=mix(vec3(.29,.30,.32),vec3(.81,.83,.84),ceiling);
    float flag=strip(r.x+.19*r.z-.13,.14,.19)*smoothstep(-.38,.18,-r.z);
    room*=1.-.73*flag;
    float window=strip(r.x+.48+.18*r.y,.17,.12)*smoothstep(-.38,.36,r.z);
    float bar=strip(r.y+.53+.24*r.x,.045,.055);
    return room+vec3(.46,.45,.42)*window+vec3(.55,.58,.63)*bar;
}
void main(){
    vec3 n=normalize(vN), v=normalize(eye-vPos), r=reflect(-v,n);
    float mat=vMat.x;
    if(mat>7.5 && mat<8.5) {
        // A rotating glass marble with a pearl/violet inclusion. The clear
        // outer skin reflects the room independently of the coloured core.
        vec3 bn=vec3(n.x*uSpin.x+n.y*uSpin.y,-n.x*uSpin.y+n.y*uSpin.x,n.z);
        float ribbon=bn.y+.31*bn.x+.34*sin(3.58*bn.z+2.10*bn.x);
        float pearl=smoothstep(-.16,.16,ribbon);
        vec3 wine=mix(vec3(.14,.060,.23),vec3(.58,.083,.33),smoothstep(-.44,.47,bn.z+.52*bn.x));
        vec3 body=mix(wine,vec3(.87,.86,.79),pearl);
        body=mix(body,vec3(.29,.068,.19),.82*strip(ribbon-.47,.063,.064)*smoothstep(-.35,.22,bn.x));
        float fres=.11+.62*pow(1.-max(0.,dot(n,v)),3.);
        vec3 color=mix(body*(.65+.34*max(0.,dot(n,L))),studio(r),fres);
        color+=vec3(.90,.89,.83)*pow(max(dot(reflect(-L,n),v),0.),92.);
        color+=vec3(.29,.31,.35)*strip(r.y+.55+.24*r.x,.018,.025);
        gl_FragColor=vec4(color,1.); return;
    }
    vec3 env=studio(r);
    float nl=max(0.,dot(n,L)), nv=max(0.,dot(n,v));
    float edge=pow(1.-nv,3.);
    float spec=pow(max(0.,dot(reflect(-L,n),v)),54.);
    vec3 accent=uAccent;
    float impact=uKey.x,preview=uKey.z;
    vec3 col;
    if(mat<1.5) {
        float top=1.-step(.5,mat);
        float ao=1.-.38*exp(-17.*dot(vPos.xz-uBall.xz,vPos.xz-uBall.xz))*
                     (1.-smoothstep(.22,.64,abs(vPos.y-uBall.y)));
        float brush=texture2D(uGrain,vFace*vec2(1.05,.14)+vec2(.23,.31)).r-.5;
        vec3 alloy=mix(vec3(.62,.64,.64),uPaint,.26);
        vec3 face=env*(.54+.19*edge)+alloy*(.20+.16*nl)+vec3(brush*.12+spec*.21);
        vec3 under=vec3(.05,.06,.072)+env*(.105+.18*edge)+alloy*.06;
        col=mix(under,face,top)*ao;
        col=mix(col,mix(uAccent,col,.67),preview*top*.63);
        // A single pressure wave expands from the tangent point on the playing face.
        // Its light decays independently of the mechanical return and score.
        float arrival=abs(length(vFace)-.96*(1.-impact));
        float wave=1.-smoothstep(.035,.15,arrival);
        float release=impact*impact;
        float flash=impact*(.70+.21*top)+.14*release*wave;
        col=mix(col,accent*(.38+.48*top+.14*nl)+env*.075,min(1.,flash));
        col+=vec3(.37,.29,.18)*release*wave*top;
    } else if(mat<2.5) {
        col=mix(mix(vec3(.060,.069,.075),accent,.27),accent,min(1.,impact+preview*.98));
    } else if(mat<3.5 || (mat>4.5 && mat<5.5) || (mat>6.5 && mat<7.5)) {
        float polished=mat<3.5 ? .93 : (mat<5.5 ? 1.12 : .88);
        col=vec3(.032,.041,.050)+env*polished+vec3(.23,.24,.25)*spec;
        if(mat>6.5) {
            float nearBall=1.-smoothstep(.25,.56,length(vPos-uBall.xyz));
            col=mix(col,vec3(.42,.24,.40),nearBall*.30);
        }
    } else if(mat<4.5) {
        col=vec3(.032,.039,.047)+env*.26;
    } else if(mat<6.5) {
        col=mix(vec3(.13,.14,.145),accent,min(1.,.48+preview*.52+impact));
    } else {
        col=vec3(.22,.085,.037)+env*vec3(.75,.38,.20)+vec3(.34,.18,.093)*spec;
    }
    // A judgement is attached to its scored cap only, even if the marble has moved on.
    if(mat<2.5 || (mat>5.5 && mat<6.5)) {
        vec3 result=uKey.y<0. ? vec3(1.,.286,.298) : vec3(.435,.945,.675);
        col=mix(col, result*(.67+.27*max(0.,-n.y)) + env*.075, abs(uKey.y)*.96);
    }
    gl_FragColor=vec4(col,1.);
}
)GL";

// A separate, unlit pass keeps material shading off particles and leaves the
// normal stage shaders untouched on frames without input. Scene depth occludes
// the arcs and embers when the ball or another panel passes in front of them.
static const char* cueVS = STAGE_CAMERA_GLSL R"GL(
attribute highp vec3 aPos;
attribute mediump vec3 aNormal;
attribute mediump vec2 aMat;
uniform highp vec3 uOffset;
uniform highp vec2 uTilt;
uniform highp vec4 uLens;
uniform mediump vec4 uKey; // timing phase or spark age, unused, unused, cap travel
varying lowp vec4 vColor;
void main() {
    mat2 turn=mat2(uTilt.x,uTilt.y,-uTilt.y,uTilt.x);
    vec3 p;
    if(aMat.x < 10.5) {
        float wide=abs(uKey.x);
        float radius=.265+.155*wide+aNormal.x;
        // Bracket the sphere where it will touch. Facing the camera keeps the
        // cue legible on the near-vertical pads of a fast call and response.
        vec2 center=turn*vec2(0.,-.23+uKey.w);
        p=vec3(center,0.)+(right*aPos.x+up*aPos.z)*radius-.045*forward;
        vec3 ink=vec3(.11,.16,.15), cream=vec3(.96,.93,.85), mint=vec3(.58,.90,.76);
        vColor=vec4(mix(ink,mix(cream,mint,1.-wide),aMat.y),.92-.33*wide);
    } else {
        float t=uKey.x, ease=t*(2.-t);
        vec3 dir=vec3(turn*aNormal.xy,aNormal.z);
        vec2 viewDir=vec2(dot(dir,right),dot(dir,up));
        vec2 across=vec2(-viewDir.y,viewDir.x)/max(.01,length(viewDir));
        p=dir*(.16+.54*ease+aPos.x*(1.-.48*t)) +
            (across.x*right+across.y*up)*aPos.y*(1.-.56*t);
        p.y+=.15*t*t;
        p.z+=.045;
        vec3 burnt=vec3(.92,.27,.12), amber=vec3(1.,.68,.31), core=vec3(1.,.97,.83);
        vec3 c=mix(mix(burnt,amber,aMat.y),core,smoothstep(.60,1.,aMat.y));
        vColor=vec4(c,(1.-t*t)*(.48+.52*aMat.y));
    }
    vec3 d=p+uOffset-eye;
    float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)*uLens.x,dot(d,up)*uLens.y,
        (uLens.w+uLens.z)/(uLens.w-uLens.z)*z-2.*uLens.w*uLens.z/(uLens.w-uLens.z),z);
}
)GL";
static const char* unlitFS = R"GL(
precision mediump float;
varying lowp vec4 vColor;
void main() { gl_FragColor=vColor; }
)GL";

// Draw the softly lit wall and translucent projected shadows before the solid
// geometry. The machined wall faces and the backing behind their recesses
// are sampled in world space; the camera moves past them at different rates.
static const char* wallVS = STAGE_CAMERA_GLSL R"GL(
attribute highp vec3 aPos;
uniform highp vec4 uLens;
uniform lowp vec3 uPaint;
uniform lowp vec3 uAccent;
uniform mediump vec3 uPhase; // camera position modulo the wall period, theme motif
#ifdef GL_FRAGMENT_PRECISION_HIGH
varying highp vec2 vReliefUV;
#else
varying mediump vec2 vReliefUV;
#endif
varying lowp vec4 vColor;
void main(){
    vec2 uv=aPos.xy*.5+.5;
    vec3 ray=forward+right*aPos.x/uLens.x+up*aPos.y/uLens.y;
    vec2 slope=ray.xy/min(-.09,ray.z);
    vec2 origin=uPhase.xy+eye.xy;
    vReliefUV=(origin+slope*(-1.35-eye.z))*.625;
    vec2 bounce=origin+slope*(-2.55-eye.z);
    float farLight=.5+.5*sin((bounce.x+bounce.y)*.2617994);
    float light=.27+.48*uv.y+.14*uv.x;
    vec3 col=mix(uPaint,uAccent,light)*(.982+.029*farLight);
    gl_Position=vec4(aPos.xy,0.,1.);
    vColor=vec4(col,1.);
}
)GL";

static const char* shadowVS = STAGE_CAMERA_GLSL R"GL(
attribute highp vec3 aPos; // radial xy and radius
attribute mediump vec3 aNormal; // opacity and direction on a unit square
attribute mediump vec2 aMat;
uniform highp vec4 uLens;
uniform highp vec4 uDecals[16]; // centre, corner shape (negative for a rail), opacity
uniform highp vec4 uShadowAxes[16];
varying lowp vec4 vColor;
void main(){
    int idx=int(aMat.y+.5);
    vec4 data=uDecals[idx], axes=uShadowAxes[idx];
    vec2 box=aNormal.yz*aPos.z;
    vec2 q=mix(aPos.xy,box,max(0.,data.z));
    if(data.z<0.) q=vec2(aNormal.y*mix(1.+data.z,1.,aPos.z),box.y);
    vec2 p=data.xy+axes.xy*q.x+axes.zw*q.y;
    vec3 d=vec3(p,-1.347)-eye;
    float z=dot(d,forward);
    gl_Position=vec4(dot(d,right)*uLens.x,dot(d,up)*uLens.y,0.,z);
    vColor=vec4(.125,.145,.174,aNormal.x*data.w);
}
)GL";
static const char* wallFS = R"GL(
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying lowp vec4 vColor;
#ifdef GL_FRAGMENT_PRECISION_HIGH
varying highp vec2 vReliefUV;
#else
varying mediump vec2 vReliefUV;
#endif
uniform sampler2D uRelief;
void main(){
    vec3 col=vColor.rgb*(.967+.066*texture2D(uRelief,vReliefUV).r);
    gl_FragColor=vec4(col,1.);
}
)GL";
#undef STAGE_CAMERA_GLSL
} // namespace cascade
