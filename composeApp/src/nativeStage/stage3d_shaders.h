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
uniform lowp vec3 uRoom; // transmitted studio wall, shared with the backdrop palette
uniform sampler2D uGrain;
uniform mediump vec4 uFinish; // pigment, reflected light, direction and scale of brushed grain
const vec3 eye=vec3(2.80,-4.88,8.20);
const vec3 L=vec3(.657,-.243,.714);
float strip(float x,float halfWidth,float blur){
    return 1.-smoothstep(halfWidth,halfWidth+blur,abs(x));
}
// Large softboxes, a dark flag and the lit plaster floor remain fixed in the
// reflection. A change of key pitch therefore changes the finish visibly.
vec3 studio(vec3 r){
    float ceiling=smoothstep(-.58,.76,-r.y);
    vec3 room=mix(vec3(.31,.37,.41),vec3(.89,.92,.91),ceiling);
    // Broad studio flags give chrome a continuous tonal gradient instead of
    // narrow, clipped bands that shimmer on a moving rail.
    float flag=strip(r.x+.17*r.z-.16,.20,.27)*smoothstep(-.55,.22,-r.z);
    room*=1.-.48*flag;
    float window=strip(r.x+.43+.15*r.y,.24,.19)*strip(r.y+.25,.45,.23)*smoothstep(-.35,.38,r.z);
    float rim=strip(r.x-.72+.09*r.y,.07,.13)*strip(r.y+.10,.58,.24);
    return room+vec3(.34,.33,.30)*window+vec3(.21,.25,.28)*rim;
}

void main(){
    vec3 n=normalize(vN), v=normalize(eye-vPos), r=reflect(-v,n);
    float mat=vMat.x;
    if(mat>7.5 && mat<8.5) {
        // Snell's law through the outer shell, then intersect a smaller inner
        // sphere. The ribbons live inside that sphere, leaving a truly clear
        // annulus rather than a swirl painted across the entire outer surface.
        float facing=max(0.,dot(n,v));
        vec3 ray=refract(-v,n,1./1.52);
        float approach=dot(n,ray);
        float discriminant=approach*approach-(1.-.52*.52);
        float entry=-approach-sqrt(max(0.,discriminant));
        vec3 inner=n+entry*ray;
        vec3 bn=vec3(inner.x*uSpin.x+inner.y*uSpin.y,-inner.x*uSpin.y+inner.y*uSpin.x,inner.z);
        float coreMask=smoothstep(-.015,.028,discriminant);
        vec3 transmission=mix(uRoom,vec3(.64,.78,.81),.17);
        transmission*=.94+.06*smoothstep(-.85,.65,-ray.y);
        float twist=bn.x+.24*sin(4.3*bn.y+3.0*bn.z);
        float ribbon=(1.-smoothstep(.060,.115,abs(twist)))*coreMask;
        float folded=(1.-smoothstep(.045,.095,abs(twist+.27-.16*bn.y)))*coreMask;
        float seam=bn.y-.19*sin(4.8*bn.z-2.0*bn.x)-.13;
        float gold=(1.-smoothstep(.035,.075,abs(seam)))*smoothstep(-.40,.15,bn.x)*coreMask;
        vec3 cobalt=mix(vec3(.032,.22,.54),vec3(.035,.61,.54),smoothstep(-.42,.46,bn.y));
        vec3 body=mix(transmission,cobalt,ribbon*.93);
        body=mix(body,vec3(.26,.16,.49),folded*.80);
        body=mix(body,vec3(.93,.62,.16),gold*.90);
        // A faint thickness line follows the internal ribbon, not the sphere rim.
        float edgeLine=(1.-smoothstep(.008,.023,abs(abs(twist)-.062)))*coreMask;
        body=mix(body,vec3(.43,.77,.73),edgeLine*.27);
        float fres=.042+.958*pow(1.-facing,5.);
        body=mix(body,vec3(.55,.70,.73),pow(1.-facing,2.)*.15);
        vec3 color=mix(body,studio(r),fres*.86);
        float softbox=strip(r.x+.43+.15*r.y,.18,.08)*strip(r.y+.25,.35,.12)*smoothstep(-.20,.40,r.z);
        color=mix(color,vec3(.99,.99,.96),softbox*(.15+.55*fres));
        float highlight=pow(max(dot(reflect(-L,n),v),0.),110.);
        color+=vec3(.70,.72,.70)*highlight;
        float caustic=pow(max(0.,dot(n,normalize(vec3(-.30,.63,.72)))),14.);
        color+=vec3(.075,.13,.13)*caustic*(1.-coreMask*.5);
        float coreOpacity=max(ribbon*.95,max(folded*.86,gold*.95));
        float shellOpacity=max(fres,max(softbox*.55,highlight*.80));
        float opacity=.34+.66*max(coreOpacity,shellOpacity);
        gl_FragColor=vec4(color,opacity); return;
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
        float brush=texture2D(uGrain,vFace*uFinish.zw+vec2(.23,.31)).r-.5;
        vec3 alloy=mix(vec3(.62,.64,.64),uPaint,uFinish.x);
        vec3 face=env*(uFinish.y+.17*edge)+alloy*(.84-uFinish.y+.22*nl)+vec3(brush*.11+spec*.21);
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
        col=vec3(.046,.058,.065)+env*(polished*.83)+vec3(.13,.15,.16)*spec;
        if(mat>6.5) {
            float nearBall=1.-smoothstep(.25,.56,length(vPos-uBall.xyz));
            col=mix(col,vec3(.42,.24,.40),nearBall*.30);
        }
    } else if(mat<4.5) {
        col=vec3(.065,.075,.084)+env*.24;
    } else if(mat<6.5) {
        col=mix(vec3(.13,.14,.145),accent,min(1.,.48+preview*.52+impact));
    } else if(mat<11.5) {
        col=vec3(.22,.085,.037)+env*vec3(.75,.38,.20)+vec3(.34,.18,.093)*spec;
    } else if(mat<12.5) {
        // Ceramic-like ivory: bright broad faces, a satin highlight and just
        // enough Fresnel reflection for a curved lip to read at phone size.
        vec3 halfVector=normalize(L+v);
        float sheen=pow(max(0.,dot(n,halfVector)),42.);
        float ambient=.83+.14*max(0.,-n.y)+.08*nl;
        float fres=.028+.16*edge;
        vec3 ivory=mix(vec3(.97,.968,.942),uPaint,.14);
        col=ivory*ambient*(1.-fres)+env*fres+vec3(.085*sheen);
    } else if(mat<13.5) {
        // Softer satin ebony shows its sculpted edges without chrome-like glitter.
        float sheen=pow(max(0.,dot(n,normalize(L+v))),34.);
        col=vec3(.027,.034,.041)*(.85+.18*nl)+env*(.10+.20*edge)+vec3(.065,.075,.085)*sheen;
    } else if(mat<14.5) {
        // Fine lengthwise fibres live in local key coordinates, so neither
        // camera travel nor the short key recoil can make the wood swim.
        float grain=texture2D(uGrain,vFace*vec2(.72,.15)+vec2(.19,.43)).r;
        float fibre=.5+.5*sin(vFace.x*31.+sin(vFace.y*6.)*.65+grain*1.8);
        vec3 wood=mix(vec3(.43,.265,.15),vec3(.57,.37,.22),grain);
        wood*=.98+.025*fibre;
        col=wood*(.72+.38*nl)+env*(.035+.13*edge)+vec3(.08,.059,.033)*spec;
    } else if(mat<15.5) {
        // Lacquered resonator tubes have a soft body reflection and a narrow
        // clear-coat highlight. Their inner lip uses a separate dark material.
        vec3 plastic=mix(vec3(.22,.12,.70),uAccent,.72);
        float sheen=pow(max(0.,dot(n,normalize(L+v))),42.);
        col=plastic*(.62+.32*nl)+env*(.055+.20*edge)+vec3(.20,.22,.25)*sheen;
    } else {
        col=mix(vec3(.031,.020,.074),uAccent*.11,.52)*(.67+.30*nl)+env*.025;
    }
    if(mat>11.5 && mat<15.5) {
        float distance=length(vPos.xz-uBall.xz);
        float ao=1.-.30*exp(-19.*distance*distance)*(1.-smoothstep(.22,.62,abs(vPos.y-uBall.y)));
        col*=ao;
        // A narrow warm response leaves the instrument material visible.
        float pulse=impact*impact;
        col=mix(col,accent*(.66+.22*nl)+env*.10,pulse*.42+preview*.045);
    }
    // A judgement is attached to its scored cap only, even if the marble has moved on.
    if(mat<2.5 || (mat>5.5 && mat<6.5) || (mat>11.5 && mat<15.5)) {
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
    vColor=vec4(.18,.205,.22,aNormal.x*data.w);
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
    // Broad studio plaster stays clean in motion; subpixel grain shimmer is absent.
    vec3 col=vColor.rgb;
    gl_FragColor=vec4(col,1.);
}
)GL";
#undef STAGE_CAMERA_GLSL
} // namespace cascade
