package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 可输入 + 实时补全的下拉框（施力点的红石频道就是它：边打字边筛物品注册名）。
 *
 * <p>文本真值在 {@link TextField#model}（一份 {@link Prop}）里，补全列表只是它的<b>投影</b>；
 * 弹出层统一走 {@link Overlay}，所以「选完不消失」「点外面不关」这类问题只有一套规则要维护。
 */
public class ComboBox extends Container {

    public final TextField field = new TextField();
    /** 全部候选（补全来源）。 */
    public Supplier<List<String>> suggestionSource = List::of;
    /** 「提交」通知（选择/回车之后）；不负责改值。 */
    @Nullable
    public Consumer<String> onCommit;
    /** 最多显示多少条补全。 */
    public int maxSuggestions = 8;
    /** 是否允许点击箭头强制展开全部候选。 */
    public boolean arrowExpands = true;
    /**
     * 给候选配一个**图集精灵**（方块贴图预览用）；返回 null 就只画文字。
     *
     * <p>⚠ 必须是**图集精灵**而不是贴图文件：方块贴图在图集里，
     * 用 {@code Entry.icon(ResourceLocation)} 会画出图集左上角那 16×16（见 UiDraw.sprite 的注释）。
     */
    @Nullable
    public java.util.function.Function<String,
            net.minecraft.client.renderer.texture.TextureAtlasSprite> spriteFor;

    @Nullable
    private String lastQuery;
    /**
     * 「这个文本不要再弹补全了」。
     *
     * <p>补全列表是<b>投影</b>：每一帧看「当前文本」重新算候选。所以只要文本没变，
     * 关闭之后下一帧就会被重新算出来、又弹回去 —— 这正是用户实测反馈的
     * 「选完频道下拉菜单不消失」（以及 Esc 关不掉）。
     *
     * <p>解法是记住「为哪个文本关的」，文本一变（玩家继续打字）就自动解除。
     */
    @Nullable
    private String suppressed;
    private int highlight = -1;
    private boolean open;

    public ComboBox(final int width, final Prop<String> model) {
        this.prefW = width;
        this.prefH = 12;
        this.layout = Layouts.absolute();
        this.padRight = 9;
        field.prefW = FILL;
        field.prefH = FILL;
        field.bind(model);
        field.changed(this::onTextChanged);
        field.commit(text -> {
            close(ctxRoot);
            if (onCommit != null) {
                Cmd.run(model.key() + ".notify", () -> onCommit.accept(text));
            }
        });
        field.keyInterceptor = this::interceptKey;
        add(field);
    }

    @Nullable
    private UiContext ctxRoot;

    public ComboBox suggestionSource(final Supplier<List<String>> source) {
        this.suggestionSource = source;
        return this;
    }

    public ComboBox onCommit(final Consumer<String> listener) {
        this.onCommit = listener;
        return this;
    }

    /** 候选行带**图集精灵**预览（方块贴图）。返回 null 的候选只画文字。 */
    public ComboBox spriteFor(final java.util.function.Function<String,
            net.minecraft.client.renderer.texture.TextureAtlasSprite> provider) {
        this.spriteFor = provider;
        return this;
    }

    /** 归属命令名（日志/自检用）。 */
    public ComboBox command(final String name) {
        field.command(name);
        return this;
    }

    public String text() {
        return field.text();
    }

    public void setText(final String value) {
        field.setText(value);
        lastQuery = value;
        // 程序设置的值同样不该触发补全弹层
        suppressed = value;
    }

    private void onTextChanged(final String text) {
        // 玩家改了文本：解除静默，并先关掉旧列表（下一帧按新文本重新算）
        suppressed = null;
        lastQuery = null;
        close(ctxRoot);
    }

    private boolean interceptKey(final Integer keyCode, final Integer modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> {
                highlight = Math.min(highlight + 1, Math.max(0, suggestions().size() - 1));
                lastQuery = null;
                suppressed = null;
                return true;
            }
            case GLFW.GLFW_KEY_UP -> {
                highlight = Math.max(highlight - 1, 0);
                lastQuery = null;
                suppressed = null;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                final List<String> list = suggestions();
                if (open && highlight >= 0 && highlight < list.size()) {
                    choose(list.get(highlight));
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (open) {
                    dismiss();
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** 玩家主动关掉列表（Esc / 点箭头收起）：记住「这个文本不要再弹」。 */
    private void dismiss() {
        suppressed = text();
        close(ctxRoot);
    }

    /** 选中一个候选：写值 + 通知，并压住补全（否则下一帧又弹出来）。 */
    private void choose(final String chosen) {
        field.setText(chosen);
        suppressed = chosen;
        lastQuery = chosen;
        close(ctxRoot);
        if (onCommit != null) {
            Cmd.run("combo.pick", () -> onCommit.accept(chosen));
        }
    }

    private List<String> suggestions() {
        final String query = text().trim().toLowerCase();
        final List<String> out = new ArrayList<>();
        for (final String candidate : suggestionSource.get()) {
            if (candidate == null) {
                continue;
            }
            if (query.isEmpty() || candidate.toLowerCase().contains(query)) {
                out.add(candidate);
                if (out.size() >= maxSuggestions) {
                    break;
                }
            }
        }
        return out;
    }

    private void close(@Nullable final UiContext ctx) {
        if (ctx != null) {
            Overlay.close(ctx, this);
        }
        open = false;
        highlight = -1;
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        ctxRoot = ctx;
        super.update(ctx, dt);
        if (Overlay.isOpen(ctx, this) != open) {
            open = Overlay.isOpen(ctx, this);
            if (!open) {
                highlight = -1;
                // 被外部关掉（点到了列表外面）：同样要压住，否则下一帧又会弹回来。
                suppressed = text();
            }
        }
        final boolean focused = ctx.root != null && ctx.root.focused == field;
        if (!focused) {
            if (open) {
                close(ctx);
            }
            lastQuery = null;
            return;
        }
        // 玩家为这个文本关过列表：不再重算（文本一改就自动解除，见 onTextChanged）
        if (suppressed != null) {
            if (suppressed.equals(text())) {
                return;
            }
            suppressed = null;
        }
        if (lastQuery != null && lastQuery.equals(text()) && open) {
            return;
        }
        lastQuery = text();
        final List<String> list = suggestions();
        if (list.isEmpty()) {
            close(ctx);
            return;
        }
        final MenuList menu = new MenuList(Math.max(w, 110));
        for (int i = 0; i < list.size(); i++) {
            final String candidate = list.get(i);
            final MenuList.Entry entry =
                    new MenuList.Entry(Component.literal(candidate), () -> choose(candidate))
                            .checked(i == highlight);
            if (spriteFor != null) {
                // 方块贴图预览：图集精灵（不是贴图文件）
                entry.sprite(spriteFor.apply(candidate));
            }
            menu.add(entry);
        }
        Overlay.openBelow(ctx, this, menu, x, y, h);
        open = true;
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (arrowExpands && button == 0 && mx >= x + w - padRight) {
            if (open) {
                dismiss();
            } else {
                lastQuery = null;
                suppressed = null; // 点箭头 = 主动要列表，解除静默
                highlight = -1;
                ctx.root.setFocus(field, ctx);
            }
            return true;
        }
        return super.mouseDown(ctx, mx, my, button);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        super.render(ctx, g);
        final UiTheme th = ctx.theme;
        final double ax = x + w - 4.5;
        final double ay = y + h / 2.0 + (open ? -0.6 : 0.6);
        final double flip = open ? -1.0 : 1.0;
        UiVector.line(g, ax - 2.0, ay - 1.0 * flip, ax, ay + 1.0 * flip, 1.4f, th.textDim);
        UiVector.line(g, ax, ay + 1.0 * flip, ax + 2.0, ay - 1.0 * flip, 1.4f, th.textDim);
    }
}
