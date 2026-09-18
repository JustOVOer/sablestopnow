package com.ovo.sablestopnow.mate;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 一条配合关系。
 *
 * @param id        配合自身的 UUID（删除/定位用）
 * @param type      配合类型
 * @param a         参考 A（保持不动的那个结构）
 * @param b         参考 B（创建时被吸附到位的那个结构）
 * @param value     距离（方块）或角度（度）；{@link MateType.ValueKind#NONE} 时无意义
 * @param flip      配合对齐：false=同向对齐，true=反向对齐；见 {@link MateType#supportsAlignment()}
 * @param name      显示名（GUI 树里的「配合N」）；为空时由 GUI 按序号生成
 * @param owner     创建者（用来做权限判断）
 * @param ownerName 创建者名（离线也能显示）
 */
public record Mate(
        UUID id,
        MateType type,
        MateRef a,
        MateRef b,
        double value,
        boolean flip,
        @Nullable String name,
        UUID owner,
        @Nullable String ownerName) {

    /** 该配合牵扯到的两个物理结构。 */
    public boolean touches(final UUID body) {
        return this.a.body().equals(body) || this.b.body().equals(body);
    }

    /** 另一端（用于 GUI 树：挂在结构下的配合要显示对面是谁）。 */
    public UUID other(final UUID body) {
        return this.a.body().equals(body) ? this.b.body() : this.a.body();
    }

    /** 以 {@code body} 为“本侧”时的参考。 */
    public MateRef refOf(final UUID body) {
        return this.a.body().equals(body) ? this.a : this.b;
    }

    /** 以 {@code body} 为“本侧”时的对面参考。 */
    public MateRef oppositeRef(final UUID body) {
        return this.a.body().equals(body) ? this.b : this.a;
    }

    /**
     * 两条配合是否描述同一个「参考对 + 类型」（用于创建时去重）。
     * 对齐方向不同视为不同配合（同向/反向是两个不同状态）。
     */
    public boolean sameAs(final MateType otherType, final MateRef refA, final MateRef refB, final boolean otherFlip) {
        if (this.type != otherType || this.flip != otherFlip) {
            return false;
        }
        final boolean same = this.a.equals(refA) && this.b.equals(refB);
        final boolean swapped = this.a.equals(refB) && this.b.equals(refA);
        return same || swapped;
    }

    /**
     * 这条配合当前是否真的能切换「同向 / 反向对齐」。
     *
     * <p>类型支持还不够：重合（{@link MateType#COINCIDENT}）只有在<b>两侧参考都带方向</b>时才有朝向可谈，
     * 顶点—顶点重合给一个对齐按钮是没有意义的。
     */
    public boolean supportsAlignment() {
        return this.type.supportsAlignment() && this.a.hasDirection() && this.b.hasDirection();
    }

    public Mate withValue(final double newValue) {
        return new Mate(this.id, this.type, this.a, this.b, newValue, this.flip, this.name, this.owner, this.ownerName);
    }

    public Mate withFlip(final boolean newFlip) {
        return new Mate(this.id, this.type, this.a, this.b, this.value, newFlip, this.name, this.owner, this.ownerName);
    }

    public Mate withName(@Nullable final String newName) {
        return new Mate(this.id, this.type, this.a, this.b, this.value, this.flip, newName, this.owner, this.ownerName);
    }
}
