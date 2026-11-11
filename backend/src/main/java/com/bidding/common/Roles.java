package com.bidding.common;

/**
 * 角色常量收口，只此一份。
 *
 * <p>必须全小写：库里 {@code users.role} 存的就是 {@code admin/tender/manufacturer}
 * （schema.sql 注释写大写、seed.sql 实际写小写，早期版本按大写写 switch 导致 100% 403）。
 *
 * <p>判定一律用 {@code equalsIgnoreCase} + if/else，不用 {@code switch}——
 * switch 的 case 是精确匹配，大小写不一致时三个分支全不命中、一律落 default 抛 403，
 * 这正是早期 bug 的结构性成因。
 */
public final class Roles {

    public static final String ADMIN = "admin";
    public static final String TENDER = "tender";
    public static final String MANUFACTURER = "manufacturer";

    private Roles() {
    }
}
