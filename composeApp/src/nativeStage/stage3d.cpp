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
    ~Buffer() {
        if (vbo)
            glDeleteBuffers(1, &vbo);
        if (ibo)
            glDeleteBuffers(1, &ibo);
    }
    void upload(const Mesh& mesh) {
        count = static_cast<GLsizei>(mesh.indices.size());
        if (!count)
            return;
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, mesh.verts.size() * sizeof(Vertex), mesh.verts.data(), GL_STATIC_DRAW);
        glGenBuffers(1, &ibo);
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
    GLint lens = -1, offset = -1, tilt = -1, key = -1, ball = -1, spin = -1, wallLight = -1, decals = -1;
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
        lens = glGetUniformLocation(id, "uLens");
        offset = glGetUniformLocation(id, "uOffset");
        tilt = glGetUniformLocation(id, "uTilt");
        key = glGetUniformLocation(id, "uKey");
        ball = glGetUniformLocation(id, "uBall");
        spin = glGetUniformLocation(id, "uSpin");
        wallLight = glGetUniformLocation(id, "uWallLight");
        decals = glGetUniformLocation(id, "uDecals[0]");
        return true;
    }
};
struct Piece {
    float x = 0, y = 0, side = 0, style = 0, slope = 0, tiltCos = 1, tiltSin = 0;
    std::vector<V> route, lamps;
    Buffer rail;
};
struct Stage {
    Program scene, wall;
    Buffer wallMesh, marble;
    std::array<Buffer, 8> keys; // short vs full, mount side, accent vs plain
    std::vector<Piece> pieces;
    GLsizei backdropIndices = 0, shadowIndices = 0;
    bool init() {
        if (!scene.link(sceneVS, sceneFS) || !wall.link(wallVS, wallFS))
            return false;
        Mesh w;
        // Lighting changes slowly across the wall; interpolate it over a small
        // grid and the shadow penumbrae over concentric rings. The full-screen
        // fragment shader does no lighting work or texture reads.
        constexpr int columns = 16, rows = 24, segments = 16, rings = 5;
        for (int y = 0; y <= rows; ++y)
            for (int x = 0; x <= columns; ++x)
                w.point({2.f * x / columns - 1, 2.f * y / rows - 1, 0}, {0, 0, 1}, Enamel);
        for (int y = 0; y < rows; ++y)
            for (int x = 0; x < columns; ++x) {
                int a = y * (columns + 1) + x;
                w.quad(a, a + 1, a + columns + 2, a + columns + 1);
            }
        backdropIndices = static_cast<GLsizei>(w.indices.size());
        for (int slot = 0; slot < 16; ++slot) {
            int center = w.point({0, 0, 0}, {1, 0, 0}, Skirt, slot);
            for (int j = 1; j <= rings; ++j) {
                float r = static_cast<float>(j) / rings;
                float t = std::max(0.f, (r - .14f) / .86f);
                float opacity = 1 - t * t * (3 - 2 * t);
                for (int k = 0; k < segments; ++k) {
                    float a = 2.f * Pi * k / segments;
                    w.point({r * std::cos(a), r * std::sin(a), 0}, {opacity, 0, 0}, Skirt, slot);
                }
                for (int k = 0; k < segments; ++k) {
                    int a = center + 1 + (j - 1) * segments + k,
                        b = center + 1 + (j - 1) * segments + (k + 1) % segments;
                    if (j == 1)
                        w.tri(center, a, b);
                    else
                        w.quad(a - segments, b - segments, b, a);
                }
            }
        }
        shadowIndices = (static_cast<GLsizei>(w.indices.size()) - backdropIndices) / 16;
        wallMesh.upload(w);
        for (int i = 0; i < 8; ++i)
            keys[i].upload(keyMesh(i & 4, (i & 2) ? 1.f : -1.f, i & 1));
        Mesh ball;
        ellipsoid(ball, {0, 0, 0}, {BallRadius, BallRadius, BallRadius}, Glass, 36, 24);
        marble.upload(ball);
        return true;
    }
    void course(const float* key, int count, const float* rails, int nr, const float* leds, int nl) {
        pieces.clear();
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
        auto assign = [&](const float* data, int rows, bool lamp) {
            for (int i = 0; data && i < rows; ++i) {
                int k = static_cast<int>(data[i * 3]);
                if (k < 0 || k >= count)
                    continue;
                Piece& p = pieces[k];
                V local{data[i * 3 + 1] - p.x, data[i * 3 + 2] - p.y, 0};
                (lamp ? p.lamps : p.route).push_back(local);
            }
        };
        assign(rails, nr, false);
        assign(leds, nl, true);
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
        const int active = static_cast<int>(f[5]), roll = static_cast<int>(f[7]),
                  next = static_cast<int>(f[9]);
        const V ball{f[0] - cameraX, f[1] - cameraY, f[2]};
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
        glViewport(0, 0, width, height);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        glDepthMask(GL_TRUE);
        glDisable(GL_DEPTH_TEST);
        glClear(GL_DEPTH_BUFFER_BIT);
        for (GLuint i = 0; i < 3; ++i)
            glEnableVertexAttribArray(i);
        glUseProgram(wall.id);
        glUniform4f(wall.lens, lensX, lensY, .20f, 45.f);
        const V rel = ball - eye;
        float bz = std::max(1.f, dot(rel, {-.279687f, .487454f, -.827155f}));
        glUniform4f(wall.wallLight, dot(rel, {.947306f, 0, -.32033f}) * lensX / bz,
                    dot(rel, {-.156135f, -.873148f, -.461818f}) * lensY / bz, strength, aspect);
        wallMesh.bind();
        glDrawElements(GL_TRIANGLES, backdropIndices, GL_UNSIGNED_SHORT, nullptr);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        float decals[16 * 4];
        int used = 0;
        auto flush = [&]() {
            if (!used)
                return;
            glUniform4fv(wall.decals, used, decals);
            glDrawElements(GL_TRIANGLES, used * shadowIndices, GL_UNSIGNED_SHORT,
                           reinterpret_cast<void*>(backdropIndices * sizeof(uint16_t)));
            used = 0;
        };
        auto shadow = [&](float x, float y, float ballType, float slope) {
            decals[4 * used] = x;
            decals[4 * used + 1] = y;
            decals[4 * used + 2] = ballType;
            decals[4 * used + 3] = slope;
            if (++used == 16)
                flush();
        };
        for (auto it = lo; it != hi; ++it) {
            if (it->style < 0.f)
                continue;
            float center = keyCenterX(it->route.size() >= 2, it->side);
            shadow(it->x - cameraX + center * it->tiltCos,
                   it->y - cameraY + center * it->tiltSin, 0, it->slope);
        }
        shadow(ball.x, ball.y, 1, 0);
        flush();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
        glEnable(GL_CULL_FACE);
        glDepthFunc(GL_LEQUAL);
        glUseProgram(scene.id);
        glUniform4f(scene.lens, lensX, lensY, .20f, 45.f);
        glUniform4f(scene.ball, ball.x, ball.y, ball.z, strength);
        glUniform2f(scene.spin, std::cos(f[4]), std::sin(f[4]));
        for (auto it = lo; it != hi; ++it) {
            Piece& p = *it;
            int i = static_cast<int>(it - pieces.begin());
            bool guided = p.route.size() >= 2;
            // Bounding boxes for the two slab clip planes, including mounting
            // hardware and the full pitched guide from its key to its release lip.
            const V halfRun = guided ? p.route.back() * .5f : V{0, 0, 0};
            V center{p.x - cameraX + halfRun.x, p.y - cameraY + .22f + halfRun.y, -.32f};
            V halfSize{.76f + std::abs(halfRun.x), .64f + std::abs(halfRun.y), 1.06f};
            bool clipped = false;
            for (float sign : {-1.f, 1.f}) {
                const V plane =
                    V{-.156135f, -.873148f, -.461818f} * (lensY * sign) - V{-.279687f, .487454f, -.827155f};
                const float radius = std::abs(plane.x) * halfSize.x + std::abs(plane.y) * halfSize.y +
                                     std::abs(plane.z) * halfSize.z;
                if (dot(center - eye, plane) > radius)
                    clipped = true;
            }
            if (clipped)
                continue;
            glUniform3f(scene.offset, p.x - cameraX, p.y - cameraY, 0);
            if (p.style >= 0.f) {
                int shape = (guided ? 4 : 0) + (p.side > 0 ? 2 : 0) + (p.style == 1.f ? 1 : 0);
                float impact = i == active ? strength : 0.f;
                bool upcoming = i == next && p.style > .5f;
                glUniform4f(scene.key, impact, p.style, upcoming ? 1.f : 0.f, -2.f);
                glUniform2f(scene.tilt, p.tiltCos, p.tiltSin);
                keys[shape].bind();
                keys[shape].draw();
            }
            if (guided) {
                if (!p.rail.count)
                    p.rail.upload(railMesh(p.route, p.lamps, p.style < 0.f));
                // Rails are already built in world axes from the physics samples.
                // A finished guide keeps a dim record of crossed lamps. Using the
                // lamp count puts the brighter moving highlight past the last lamp.
                float crossed = i == roll ? f[8] : (i <= active ? static_cast<float>(p.lamps.size()) : -2.f);
                glUniform4f(scene.key, 0.f, p.style, i == roll ? f[11] : 0.f, crossed);
                glUniform2f(scene.tilt, 1.f, 0.f);
                p.rail.bind();
                p.rail.draw();
            }
        }
        glUniform3f(scene.offset, ball.x, ball.y, ball.z);
        glUniform2f(scene.tilt, 1.f, 0.f);
        marble.bind();
        marble.draw();
        glDisable(GL_CULL_FACE);
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
extern "C" void stage3d_set_course(void* stage, const float* keys, int nk, const float* rails, int nr,
                                   const float* lamps, int nl) {
    if (stage)
        static_cast<cascade::Stage*>(stage)->course(keys, nk, rails, nr, lamps, nl);
}
extern "C" void stage3d_draw(void* stage, int width, int height, const float* frame) {
    if (stage)
        static_cast<cascade::Stage*>(stage)->frame(width, height, frame);
}
