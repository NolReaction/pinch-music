#include "stage3d.h"
#if defined(__APPLE__)
#include <OpenGLES/ES2/gl.h>
#else
#include <GLES2/gl2.h>
#endif
#include "stage3d_mesh.h"
#include "stage3d_shaders.h"
#include <array>
#include <cstddef>
#include <cstdio>
#include <utility>

namespace cascade {
struct Buffer {
    GLuint vbo = 0, ibo = 0;
    GLsizei count = 0;
    Buffer() = default;
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
    Buffer(Buffer&& b) noexcept : vbo(b.vbo), ibo(b.ibo), count(b.count) {
        b.vbo = b.ibo = 0;
        b.count = 0;
    }
    ~Buffer() { release(); }
    void release() {
        if (vbo)
            glDeleteBuffers(1, &vbo);
        if (ibo)
            glDeleteBuffers(1, &ibo);
        vbo = ibo = 0;
        count = 0;
    }
    void upload(const Mesh& mesh) {
        count = static_cast<GLsizei>(mesh.indices.size());
        if (!count)
            return;
        if (!vbo) glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, mesh.verts.size() * sizeof(Vertex), mesh.verts.data(), GL_STATIC_DRAW);
        if (!ibo) glGenBuffers(1, &ibo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size() * sizeof(uint16_t), mesh.indices.data(),
                     GL_STATIC_DRAW);
    }
    void bind() const {
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ibo);
        glVertexAttribPointer(0, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex), nullptr);
        glVertexAttribPointer(1, 3, GL_FLOAT, GL_FALSE, sizeof(Vertex),
                              reinterpret_cast<void*>(offsetof(Vertex, n)));
        glVertexAttribPointer(2, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex),
                              reinterpret_cast<void*>(offsetof(Vertex, material)));
    }
    void draw() const {
        glDrawElements(GL_TRIANGLES, count, GL_UNSIGNED_SHORT, nullptr);
    }
};
static GLuint compile(GLenum kind, const char* source) {
    GLuint shader = glCreateShader(kind);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {};
        glGetShaderInfoLog(shader, sizeof log, nullptr, log);
        std::fprintf(stderr, "Cascade shader: %s\n", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}
struct Program {
    GLuint id = 0;
    GLint finish = -1, lens = -1, offset = -1, tilt = -1, key = -1, ball = -1, spin = -1, paint = -1, accent = -1, room = -1, phase = -1, decals = -1, shadowAxes = -1;
    ~Program() {
        if (id)
            glDeleteProgram(id);
    }
    bool link(const char* vs, const char* fs) {
        GLuint v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
        if (v && f) {
            id = glCreateProgram();
            glAttachShader(id, v);
            glAttachShader(id, f);
            glBindAttribLocation(id, 0, "aPos");
            glBindAttribLocation(id, 1, "aNormal");
            glBindAttribLocation(id, 2, "aMat");
            glLinkProgram(id);
        }
        if (v)
            glDeleteShader(v);
        if (f)
            glDeleteShader(f);
        GLint ok = 0;
        if (id)
            glGetProgramiv(id, GL_LINK_STATUS, &ok);
        if (!ok) {
            if (id) {
                char log[1024] = {};
                glGetProgramInfoLog(id, sizeof log, nullptr, log);
                std::fprintf(stderr, "Cascade program: %s\n", log);
            }
            return false;
        }
        finish = glGetUniformLocation(id, "uFinish");
        lens = glGetUniformLocation(id, "uLens");
        offset = glGetUniformLocation(id, "uOffset");
        tilt = glGetUniformLocation(id, "uTilt");
        key = glGetUniformLocation(id, "uKey");
        ball = glGetUniformLocation(id, "uBall");
        spin = glGetUniformLocation(id, "uSpin");
        paint = glGetUniformLocation(id, "uPaint");
        accent = glGetUniformLocation(id, "uAccent");
        room = glGetUniformLocation(id, "uRoom");
        phase = glGetUniformLocation(id, "uPhase");
        decals = glGetUniformLocation(id, "uDecals[0]");
        shadowAxes = glGetUniformLocation(id, "uShadowAxes[0]");
        return true;
    }
};
static void colour(GLint location, float packed) {
    const auto rgb = static_cast<unsigned>(packed);
    glUniform3f(location, ((rgb >> 16) & 255) / 255.f, ((rgb >> 8) & 255) / 255.f, (rgb & 255) / 255.f);
}
static void roomColour(GLint location, float lightPacked, float wallPacked) {
    const auto light = static_cast<unsigned>(lightPacked), wall = static_cast<unsigned>(wallPacked);
    auto channel = [&](int shift) { return (.55f * ((light >> shift) & 255) + .45f * ((wall >> shift) & 255)) / 255.f; };
    glUniform3f(location, channel(16), channel(8), channel(0));
}
struct Piece {
    float x = 0, y = 0, side = 0, style = 0, slope = 0, tiltCos = 1, tiltSin = 0;
    std::vector<V> route;
    V routeMin{}, routeMax{};
    V keyCenter{}, keyHalf{};
    Buffer rail;
    int shape() const {
        return (route.size() >= 2 ? 4 : 0) + (side > 0 ? 2 : 0) + (style == 1.f ? 1 : 0);
    }
};
struct Stage {
    Program scene, wall, shadows, cues;
    Buffer wallMesh, marble, brackets, sparks;
    std::array<Buffer, 8> keys; // short vs full, mount side, accent vs plain
    std::vector<Piece> pieces;
    GLsizei backdropIndices = 0, shadowIndices = 0;
    GLuint relief = 0;
    int reliefMotif = -1;
    std::array<bool, 8> usedKeys{};
    int keyMotif = -1;
    bool warmedCues = false;
    bool streamRails = false;
    std::vector<int> railRows;
    static constexpr size_t maxResidentRails = 48;
    std::vector<size_t> residentRails;
    ~Stage() {
        if (relief) glDeleteTextures(1, &relief);
    }
    void prepareKeys(int motif) {
        if (keyMotif == motif) return;
        const auto& design = keyDesign(motif);
        std::array<V, 8> centers{}, extents{};
        for (int i = 0; i < 8; ++i) {
            if (!usedKeys[i]) continue;
            auto mesh = keyMesh(i & 4, (i & 2) ? 1.f : -1.f, i & 1, design);
            V low = mesh.verts.front().p, high = low;
            for (const auto& vertex : mesh.verts) {
                const V pos = vertex.p;
                low = {std::min(low.x, pos.x), std::min(low.y, pos.y), std::min(low.z, pos.z)};
                high = {std::max(high.x, pos.x), std::max(high.y, pos.y + .09f * vertex.detail),
                        std::max(high.z, pos.z)};
            }
            centers[i] = (low + high) * .5f;
            extents[i] = (high - low) * .5f + V{.01f, .01f, .01f};
            keys[i].upload(mesh);
        }
        // Rotate the actual body and bracket bounds once, including spring travel.
        for (auto& p : pieces) {
            if (p.style < 0.f) continue;
            const V c = centers[p.shape()], h = extents[p.shape()];
            const float cs = p.tiltCos, sn = p.tiltSin;
            p.keyCenter = {c.x * cs - c.y * sn, c.y * cs + c.x * sn, c.z};
            p.keyHalf = {h.x * cs + h.y * std::abs(sn), h.y * cs + h.x * std::abs(sn), h.z};
        }
        keyMotif = motif;
    }
    void uploadRail(size_t row) {
        Piece& p = pieces[railRows[row]];
        p.rail.upload(railMesh(p.route, p.style < 0.f));
    }
    void streamRailWindow(size_t begin, size_t end, size_t focus) {
        // A seek can move the window by an arbitrary distance. Bound both uploads
        // and retirement, always on the owning GL thread before the first draw.
        int retired = 0;
        for (size_t i = 0; i < residentRails.size() && retired < 2;) {
            const size_t row = residentRails[i];
            if (row >= begin && row < end) {
                ++i;
                continue;
            }
            pieces[railRows[row]].rail.release();
            residentRails[i] = residentRails.back();
            residentRails.pop_back();
            ++retired;
        }
        if (residentRails.size() >= maxResidentRails) return;
        size_t closest = end;
        for (size_t i = begin; i < end; ++i) {
            if (pieces[railRows[i]].rail.count) continue;
            if (closest == end || std::abs(int(i) - int(focus)) < std::abs(int(closest) - int(focus))) closest = i;
        }
        if (closest != end) {
            uploadRail(closest);
            residentRails.push_back(closest);
        }
    }
    void bindWallRelief(int motif) {
        glActiveTexture(GL_TEXTURE0);
        if (!relief) glGenTextures(1, &relief);
        glBindTexture(GL_TEXTURE_2D, relief);
        if (reliefMotif == motif) return;
        // Seamless mineral grain and a finer directional tooth. Both the wall
        // and the brushed faces use this small swatch at their own physical scales.
        constexpr int side = 128;
        std::vector<uint8_t> texels(side * side);
        const unsigned seed = (static_cast<unsigned>(motif) + 1u) * 1447u;
        auto noise = [seed](int x, int y) {
            unsigned n = (static_cast<unsigned>(x) * 1973u + static_cast<unsigned>(y) * 9277u + seed);
            n = (n ^ (n >> 11u)) * 0x7feb352du;
            n = (n ^ (n >> 15u)) * 0x846ca68bu;
            return static_cast<float>((n ^ (n >> 16u)) & 255u) / 255.f;
        };
        auto cloud = [&](int x, int y, int step) {
            int n = side / step, a = x / step, b = y / step;
            float u = (x % step) / static_cast<float>(step), v = (y % step) / static_cast<float>(step);
            u *= u * (3.f - 2.f * u);
            v *= v * (3.f - 2.f * v);
            float low = (1.f - u) * noise(a, b) + u * noise((a + 1) % n, b);
            float high = (1.f - u) * noise(a, (b + 1) % n) + u * noise((a + 1) % n, (b + 1) % n);
            return (1.f - v) * low + v * high;
        };
        for (int y = 0; y < side; ++y) {
            for (int x = 0; x < side; ++x) {
                float grain = .46f * noise(x, y) + .32f * cloud(x, y, 8) + .22f * cloud(x, y, 32);
                texels[y * side + x] = static_cast<uint8_t>(255.f * grain);
            }
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, side, side, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE, texels.data());
        reliefMotif = motif;
    }
    bool init() {
        if (!scene.link(sceneVS, sceneFS) || !wall.link(wallVS, wallFS) ||
            !shadows.link(shadowVS, unlitFS) || !cues.link(cueVS, unlitFS))
            return false;
        Mesh w;
        // Interpolate the wall lighting on a coarse grid and the shadow penumbrae on rings.
        constexpr int columns = 16, rows = 24, segments = 16;
        constexpr float shadowRadii[] = {.40f, .60f, .80f, 1.f};
        for (int y = 0; y <= rows; ++y)
            for (int x = 0; x <= columns; ++x)
                w.point({2.f * x / columns - 1, 2.f * y / rows - 1, 0}, {0, 0, 1}, Face);
        for (int y = 0; y < rows; ++y)
            for (int x = 0; x < columns; ++x) {
                int a = y * (columns + 1) + x;
                w.quad(a, a + 1, a + columns + 2, a + columns + 1);
            }
        backdropIndices = static_cast<GLsizei>(w.indices.size());
        for (int slot = 0; slot < 16; ++slot) {
            int center = w.point({0, 0, 0}, {1, 0, 0}, Skirt, slot);
            for (int j = 0; j < 4; ++j) {
                float r = shadowRadii[j];
                float t = std::max(0.f, (r - .40f) / .60f);
                float opacity = 1 - t * t * (3 - 2 * t);
                for (int k = 0; k < segments; ++k) {
                    float a = 2.f * Pi * k / segments;
                    const float x = std::cos(a), y = std::sin(a);
                    const float side = std::max(std::abs(x), std::abs(y));
                    w.point({r * x, r * y, r}, {opacity, x / side, y / side}, Skirt, slot);
                }
                for (int k = 0; k < segments; ++k) {
                    int a = center + 1 + j * segments + k,
                        b = center + 1 + j * segments + (k + 1) % segments;
                    if (j == 0)
                        w.tri(center, a, b);
                    else
                        w.quad(a - segments, b - segments, b, a);
                }
            }
        }
        shadowIndices = (static_cast<GLsizei>(w.indices.size()) - backdropIndices) / 16;
        wallMesh.upload(w);
        Mesh ball;
        ellipsoid(ball, {0, 0, 0}, {BallRadius, BallRadius, BallRadius}, Glass, 36, 24);
        marble.upload(ball);
        brackets.upload(timingArcs());
        sparks.upload(contactSparks());
        return true;
    }
    void course(const float* key, int count, const float* rails, int nr) {
        pieces.clear();
        railRows.clear();
        residentRails.clear();
        usedKeys.fill(false);
        keyMotif = -1;
        if (!key || count <= 0)
            return;
        pieces.resize(count);
        for (int i = 0; i < count; ++i) {
            Piece& p = pieces[i];
            const float* row = key + i * STAGE3D_KEY_STRIDE;
            p.x = row[0];
            p.y = row[1];
            p.side = row[2];
            p.style = row[3];
            p.slope = row[4];
            p.tiltCos = 1.f / std::sqrt(1.f + p.slope * p.slope);
            p.tiltSin = p.slope * p.tiltCos;
        }
        for (int i = 0; rails && i < nr; ++i) {
            int index = static_cast<int>(rails[i * 3]);
            if (index < 0 || index >= count)
                continue;
            Piece& p = pieces[index];
            V point{rails[i * 3 + 1] - p.x, rails[i * 3 + 2] - p.y, 0};
            p.route.push_back(point);
            p.routeMin.x = std::min(p.routeMin.x, point.x);
            p.routeMin.y = std::min(p.routeMin.y, point.y);
            p.routeMax.x = std::max(p.routeMax.x, point.x);
            p.routeMax.y = std::max(p.routeMax.y, point.y);
        }
        for (int i = 0; i < count; ++i) {
            const Piece& p = pieces[i];
            if (p.style >= 0.f) usedKeys[p.shape()] = true;
            if (p.route.size() >= 2) railRows.push_back(i);
        }
        // A station expands into both runners, the spine and mountings. Preload
        // ordinary charts; bound resident geometry on long user-authored courses.
        streamRails = nr > 4096;
        const size_t openingEnd = std::lower_bound(railRows.begin(), railRows.end(), 48) - railRows.begin();
        const size_t initial = streamRails ? openingEnd : railRows.size();
        residentRails.reserve(maxResidentRails);
        for (size_t i = 0; i < initial; ++i) {
            uploadRail(i);
            if (streamRails) residentRails.push_back(i);
        }
    }
    void frame(int width, int height, const float* f) {
        if (width <= 0 || height <= 0 || !f)
            return;
        const float aspect = static_cast<float>(width) / height;
        // Pull back on long descents so the next stretch of rail enters the frame.
        // Travel is measured on the smoothed camera path; a strike never kicks the lens.
        const float run = std::clamp((f[12] - 1.4f) / 4.8f, 0.f, 1.f);
        const float framing = .98f + .18f * run * run * (3.f - 2.f * run);
        const float halfHeight = std::max(2.48f, 2.00f / aspect) * framing;
        const float lensY = 9.93f / halfHeight, lensX = lensY / aspect;
        const float cameraX = f[10], cameraY = f[3] + .26f;
        const float strength = std::max(0.f, std::min(1.f, f[6]));
        const int active = static_cast<int>(f[5]), next = static_cast<int>(f[9]);
        V ball{f[0] - cameraX, f[1] - cameraY, f[2]};
        if (f[8] > 0.f && active >= 0 && active < static_cast<int>(pieces.size())) {
            ball.x -= f[8] * pieces[active].tiltSin;
            ball.y += f[8] * pieces[active].tiltCos;
        }
        // Bound the vertical extent of all four corner rays up to the rear wall.
        // The perspective view can see farther down at one corner, especially in landscape.
        const V eye{2.80f, -4.88f, 8.20f};
        const float wallDepth = std::min(45.f, (eye.z - WallZ + .1f) /
            std::max(.001f, .827155f - .32033f / lensX - .461818f / lensY));
        const float upperRayY = .487454f - .873148f / lensY;
        const float lowerRayY = .487454f + .873148f / lensY;
        const float top = cameraY + eye.y + upperRayY * (upperRayY < 0.f ? wallDepth : .20f) - 1.5f;
        const float bottom = cameraY + eye.y + lowerRayY * wallDepth + 1.5f;
        auto lo = std::lower_bound(pieces.begin(), pieces.end(), top,
                                   [](const Piece& p, float y) { return p.y < y; });
        if (lo != pieces.begin())
            --lo; // The preceding rail can extend down into the viewport.
        auto hi = std::upper_bound(lo, pieces.end(), bottom,
                                   [](float y, const Piece& p) { return y < p.y; });
        // |clip.x| and |clip.y| must both be <= clip.w. Test a conservative
        // axis-aligned box against all four planes before submitting any vertices.
        const V forward{-.279687f, .487454f, -.827155f};
        const V right{.947306f * lensX, 0.f, -.32033f * lensX};
        const V up = V{-.156135f, -.873148f, -.461818f} * lensY;
        const V planes[] = {right - forward, right * -1.f - forward, up - forward, up * -1.f - forward};
        auto visible = [&](V center, V half) {
            const V delta = center - eye;
            for (V plane : planes) {
                if (dot(delta, plane) > std::abs(plane.x) * half.x + std::abs(plane.y) * half.y +
                                        std::abs(plane.z) * half.z) return false;
            }
            return true;
        };
        if (streamRails) {
            const auto begin = std::lower_bound(railRows.begin(), railRows.end(), int(lo - pieces.begin()) - 8);
            const auto end = std::lower_bound(begin, railRows.end(), int(hi - pieces.begin()) + 24);
            const size_t focus = std::lower_bound(begin, end, active) - railRows.begin();
            const size_t left = std::max(size_t(begin - railRows.begin()), focus - std::min(focus, maxResidentRails / 2));
            const size_t rightEdge = std::min(size_t(end - railRows.begin()), left + maxResidentRails);
            streamRailWindow(left, rightEdge, focus);
        }
        prepareKeys(static_cast<int>(f[11]));
        const auto& design = keyDesign(keyMotif);
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
        glDisable(GL_DEPTH_TEST);
        // Both attachments are redrawn. Mobile tile renderers can skip loading their old contents.
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        for (GLuint i = 0; i < 3; ++i)
            glEnableVertexAttribArray(i);
        glUseProgram(wall.id);
        bindWallRelief(static_cast<int>(f[11]));
        glUniform4f(wall.lens, lensX, lensY, .20f, 45.f);
        colour(wall.paint, f[13]);
        colour(wall.accent, f[14]);
        glUniform3f(wall.phase, std::fmod(cameraX, 24.f), std::fmod(cameraY, 24.f), f[11]);
        wallMesh.bind();
        glDrawElements(GL_TRIANGLES, backdropIndices, GL_UNSIGNED_SHORT, nullptr);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glUseProgram(shadows.id);
        glUniform4f(shadows.lens, lensX, lensY, .20f, 45.f);
        float decals[16 * 4], axes[16 * 4];
        int used = 0;
        auto flush = [&]() {
            if (!used)
                return;
            glUniform4fv(shadows.decals, used, decals);
            glUniform4fv(shadows.shadowAxes, used, axes);
            glDrawElements(GL_TRIANGLES, used * shadowIndices, GL_UNSIGNED_SHORT,
                           reinterpret_cast<void*>(backdropIndices * sizeof(uint16_t)));
            used = 0;
        };
        // Project onto the wall along the same studio light that shades the metal.
        constexpr float castX = -.92f, castY = .34f;
        auto shadow = [&](float x, float y, float ux, float uy, float vx, float vy, float square, float alpha) {
            if (!visible({x, y, -1.347f}, {std::abs(ux) + std::abs(vx) + .002f,
                                             std::abs(uy) + std::abs(vy) + .002f, 0.f})) return;
            int n = 4 * used;
            decals[n] = x; decals[n + 1] = y; decals[n + 2] = square; decals[n + 3] = alpha;
            axes[n] = ux; axes[n + 1] = uy; axes[n + 2] = vx; axes[n + 3] = vy;
            if (++used == 16) flush();
        };
        auto lineShadow = [&](V a, V b, float penumbra, float alpha) {
            const V d = b - a;
            const float length = std::hypot(d.x, d.y);
            if (length <= .001f) return;
            float extend = .5f + .50f * penumbra / length;
            shadow((a.x + b.x) * .5f, (a.y + b.y) * .5f, d.x * extend, d.y * extend,
                   -d.y * penumbra / length, d.x * penumbra / length, -std::min(1.f, penumbra / (length * extend)), alpha);
        };
        for (auto it = lo; it != hi; ++it) {
            const V origin{it->x - cameraX, it->y - cameraY, 0};
            const bool guided = it->route.size() >= 2;
            if (it->style >= 0.f) {
                const float w = guided ? .23f : .35f, center = keyCenterX(guided, it->side);
                const float cs = it->tiltCos, sn = it->tiltSin;
                const bool accented = it->style > .5f;
                const float depth = keyDepth(design, accented);
                const float back = guided ? -.585f : -.66f, zLength = .25f - back;
                if (design.instrument == 2) {
                    // Hollow pipes have a narrow cylindrical silhouette. Project
                    // their two real saddles instead of the keyboard's broad plate.
                    for (float dx : {-w*.60f,w*.60f}) {
                        const float x=origin.x+(center+dx)*cs-.245f*sn;
                        const float y=origin.y+(center+dx)*sn+.245f*cs;
                        lineShadow({x,y,0},{x+castX*1.30f,y+castY*1.30f,0},.048f,.18f);
                    }
                    shadow(origin.x+center*cs-.103f*sn+castX*-WallZ,
                           origin.y+center*sn+.103f*cs+castY*-WallZ,
                           cs*(w+.06f),sn*(w+.06f),-.165f*sn+castX*.07f,
                           .165f*cs+castY*.07f,.20f,.25f);
                } else {
                const float lift = (.25f + back) * .5f - WallZ;
                const float mountX = origin.x + center * cs - (depth + .092f) * sn;
                const float mountY = origin.y + center * sn + (depth + .092f) * cs;
                lineShadow({mountX,mountY,0}, {mountX + castX * .99f,mountY + castY * .99f,0}, .075f, .22f);
                const float turn = castY * cs - castX * sn >= 0.f ? 1.f : -1.f;
                shadow(origin.x + center * cs - depth * .5f * sn + castX * lift,
                       origin.y + center * sn + depth * .5f * cs + castY * lift,
                       cs * (w + .10f), sn * (w + .10f), castX * zLength * .5f - turn * sn * (depth * .5f + .12f),
                       castY * zLength * .5f + turn * cs * (depth * .5f + .12f), .68f, .27f);
                }
            }
            if (guided) {
                V projected = origin + V{castX * -WallZ, castY * -WallZ + .21f, 0};
                size_t start = 0;
                while (start + 1 < it->route.size()) {
                    size_t end = std::min(start + 6, it->route.size() - 1);
                    lineShadow(projected + it->route[start], projected + it->route[end], .075f, .15f);
                    start = end;
                }
            }
        }
        shadow(ball.x + castX * -WallZ, ball.y + castY * -WallZ,
               .35f, -.04f, -.07f, .34f, 0.f, .23f);
        flush();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDepthFunc(GL_LEQUAL);
        glUseProgram(scene.id);
        glUniform4f(scene.finish, design.lacquer, design.reflection, design.grainX, design.grainZ);
        colour(scene.paint, f[15]);
        colour(scene.accent, f[16]);
        roomColour(scene.room, f[13], f[14]);
        glUniform4f(scene.lens, lensX, lensY, .20f, 45.f);
        glUniform4f(scene.ball, ball.x, ball.y, ball.z, strength);
        glUniform2f(scene.spin, std::cos(f[4]), std::sin(f[4]));
        for (auto it = lo; it != hi; ++it) {
            Piece& p = *it;
            int i = static_cast<int>(it - pieces.begin());
            const V origin{p.x - cameraX, p.y - cameraY, 0};
            if (p.style >= 0.f && visible(origin + p.keyCenter, p.keyHalf)) {
                glUniform3f(scene.offset, origin.x, origin.y, 0);
                const int shape = p.shape();
                float impact = i == active ? strength : 0.f;
                bool upcoming = i == next;
                float score = 0.f;
                for (int slot = 17; slot < 25; slot += 2) {
                    if (i == static_cast<int>(f[slot])) {
                        score = f[slot + 1];
                        break;
                    }
                }
                glUniform4f(scene.key, impact, score, upcoming ? 1.f : 0.f, i == active ? f[7] : 0.f);
                glUniform2f(scene.tilt, p.tiltCos, p.tiltSin);
                keys[shape].bind();
                keys[shape].draw();
            }
            const V mid = (p.routeMax + p.routeMin) * .5f;
            const V half = (p.routeMax - p.routeMin) * .5f;
            if (p.rail.count && visible(origin + mid + V{0, .22f, -.32f}, half + V{.83f, .91f, 1.06f})) {
                glUniform3f(scene.offset, origin.x, origin.y, 0);
                // Rails are already built in world axes from the physics samples.
                glUniform4f(scene.key, 0.f, 0.f, 0.f, 0.f);
                glUniform2f(scene.tilt, 1.f, 0.f);
                p.rail.bind();
                p.rail.draw();
            }
        }
        glUniform3f(scene.offset, ball.x, ball.y, ball.z);
        glUniform2f(scene.tilt, 1.f, 0.f);
        // Opaque instruments are already drawn. The glass shell can transmit
        // their actual image while retaining depth for the later cue pass.
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
        marble.bind();
        marble.draw();
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);

        const int target = static_cast<int>(f[25]);
        if (warmedCues && target < 0 && std::none_of(f + 27, f + 31, [](float age) { return age >= 0.f; }))
            return;
        glUseProgram(cues.id);
        glUniform4f(cues.lens, lensX, lensY, .20f, 45.f);
        glDepthMask(GL_FALSE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        auto effect = [&](int index, float phase, const Buffer& mesh) {
            if (index < 0 || index >= static_cast<int>(pieces.size()) || pieces[index].style < 0.f) return;
            const Piece& p = pieces[index];
            glUniform3f(cues.offset, p.x - cameraX, p.y - cameraY, 0);
            glUniform2f(cues.tilt, p.tiltCos, p.tiltSin);
            glUniform4f(cues.key, phase, 0, 0, index == active ? f[7] : 0.f);
            mesh.bind();
            mesh.draw();
        };
        if (!warmedCues) {
            // Exercise the same blended program and mesh state used by the first hit.
            // At age 1 the last ember is fully transparent and depth writes are off.
            glUniform3f(cues.offset, ball.x, ball.y, ball.z);
            glUniform2f(cues.tilt, 1.f, 0.f);
            glUniform4f(cues.key, 1.f, 0.f, 0.f, 0.f);
            sparks.bind();
            sparks.draw();
            warmedCues = true;
        }
        if (target >= 0) effect(target, f[26], brackets);
        for (int slot = 0; slot < 4; ++slot) {
            const float age = f[27 + slot];
            if (age >= 0.f && age < 1.f) effect(static_cast<int>(f[17 + 2 * slot]), age, sparks);
        }
        glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
    }
};
} // namespace cascade
extern "C" void* stage3d_create(void) {
    auto* s = new cascade::Stage();
    if (!s->init()) {
        delete s;
        return nullptr;
    }
    return s;
}
extern "C" void stage3d_destroy(void* stage) {
    delete static_cast<cascade::Stage*>(stage);
}
extern "C" void stage3d_set_course(void* stage, const float* keys, int nk, const float* rails, int nr) {
    if (stage)
        static_cast<cascade::Stage*>(stage)->course(keys, nk, rails, nr);
}
extern "C" void stage3d_draw(void* stage, int width, int height, const float* frame) {
    if (stage)
        static_cast<cascade::Stage*>(stage)->frame(width, height, frame);
}
