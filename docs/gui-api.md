# Ogsky Shared GUI API (`core.api.gui`)

`OgskyCore.api().guis()` ([GuiService](../modules/core/src/main/java/me/ogsammaenr/ogsky/core/api/gui/GuiService.java)) provides a UI-agnostic declarative GUI contract in `modules/core` so optional Skyblock modules (`panel`, `level`, `limits`, future `warps`, `challenges`, etc.) can expose rich interactive menus and contribute summary lines without:

- depending on or importing `modules/panel`, or
- writing custom `ChestMenu` / `Slot` / anti-dupe packet mixins of their own.

When `ogsky-panel` is bundled, `PanelGuiHost` renders every registered screen inside `OgskyCustomGuiMenu`, sharing `OgskyReadOnlyChestMenu` dupe/swap/creative protections. When `ogsky-panel` is **not** bundled (e.g., `-Pmodules=core` or `-Pmodules=core,limits`), `core.guis().isAvailable()` returns `false` and `openScreen(...)` safely returns `false` so modules can fall back to chat output.

> See also: [Core Public API Reference](core-api.md) and [Module Architecture, Connections & Extension Guide](modules.md).

### Live Optional Module Screens
- **`ogsky-level`:** Contributes summary `ogsky_level:summary` (`sortOrder = 20`), screens `ogsky_level:top`, `ogsky_level:detail`, `ogsky_level:values`, and panel actions `ogsky:island_level`, `ogsky_level:island_top`, `ogsky_level:block_values`.
- **`ogsky-limits`:** Contributes summary `ogsky_limits:summary` (`sortOrder = 30`, gated by `ogsky_limits:view_limits`), filterable/sortable screen `ogsky_limits:overview`, and panel action `ogsky_limits:island_limits`.

---

## Core Contracts (`me.ogsammaenr.ogsky.core.api.gui`)

| Type | Purpose |
| --- | --- |
| `GuiService` | Central registry & dispatcher exposed via `OgskyCore.api().guis()`. |
| `GuiScreenProvider` | Functional builder (`GuiScreenModel build(GuiBuildContext context)`) evaluated when opening or refreshing a screen. |
| `GuiScreenModel` | Declarative screen layout (`LayoutMode.PAGED_GRID` 6-row shell or `LayoutMode.CUSTOM_FIXED` 1–6 rows). |
| `GuiButton` | Declarative slot item (`iconItemId`, optional `playerHeadUuid`, `count`, `name`, `lore`, `glowing`, `confirmSpec`, `onClick`). |
| `GuiBuildContext` | Read-only render context (`viewer()`, `islandId()`, `requestedPage()`, `state()`, `backScreenId()`, `backIslandId()`). |
| `GuiClickContext` | Click callback API (`actor()`, `islandId()`, `clickAction()`, `currentPage()`, `state()`, `refresh()`, `refreshWithState(k, v)`, `openScreen(...)`, `goBack()`, `close()`, `sendFeedback(...)`). |
| `GuiOpenRequest` | Immutable navigation/filter payload (`screenId`, `islandId`, `page`, `state`, `backRequest` capped at depth `16`). |
| `GuiConfirmSpec` | Two-click confirmation (`targetKey`, `promptLine`) enforced over a 10-second window by `PanelGuiHost`. |
| `GuiClickAction` | Normalized click types (`LEFT`, `RIGHT`, `SHIFT_LEFT`, `SHIFT_RIGHT`). |
| `IslandSummaryContributor` | Appends dynamic `Component` lines to the Island Summary icon (slot `8`) on the main `/is panel` Control Panel. |
| `IslandSummaryContext` | Viewer & island context (`islandId()`, `viewerUuid()`, `viewerRole()`, `viewerAdminBypass()`) passed to summary contributors. |
| `GuiHost` | Host renderer interface bound exclusively by `ogsky-panel` via `core.guis().registerHost(...)`. |

---

## Layout Modes & Slot Map

### 1. `GuiScreenModel.paged(title)` (`LayoutMode.PAGED_GRID`)

Uses the same 6-row (`54`-slot) layout convention as the main Control Panel:

```text
Row 0 (0..8)   : Free for pinned header/info buttons via builder.pinButton(slot, ...)
Rows 1..4      : 28 paged content slots (10..16, 19..25, 28..34, 37..43)
Row 5 (Footer) : 45 = Back / Close (auto-rendered: "Back to Control Panel", "Back", or "Close")
                 47 = Optional contextButton(...) (e.g. sort/filter toggle)
                 48 = Previous Page (auto-rendered when page > 0)
                 49 = Page X / Y indicator & refresh (auto-rendered)
                 50 = Next Page (auto-rendered when page + 1 < totalPages)
                 53 = Refresh (auto-rendered)
```

> Note: Footer control slots (`45`, `47`, `48`, `49`, `50`, `53`) are managed by `OgskyCustomGuiMenu`; use `contextButton(...)` instead of pinning slot `47` manually.

### 2. `GuiScreenModel.fixed(title, rows)` (`LayoutMode.CUSTOM_FIXED`)

Free-form `1..6` row menu where every interactive or informational slot is placed explicitly with `builder.pinButton(slotIndex, button)`. Empty slots are filled automatically with non-interactive filler glass panes.

---

## Module Integration Examples

### Registering a Screen, Summary Contributor, and Action in `OgskyModule.onLoad`

`OgskyCoreMod` loads optional modules alphabetically by `moduleId` (for example, `level` loads before `panel`). Because `SimpleGuiService` is initialized by `core` before optional modules load, modules can register screens and summary contributors directly inside `OgskyModule.onLoad(OgskyCoreApi)`:

```java
Identifier LEADERBOARD_SCREEN = Identifier.fromNamespaceAndPath("ogsky_level", "top");

@Override
public void onLoad(OgskyCoreApi core) {
    // 1. Add lines to slot 8 (Island Summary) of /is panel
    core.guis().registerSummaryContributor(
        Identifier.fromNamespaceAndPath("ogsky_level", "summary"),
        20,
        ctx -> levelService.findLevel(ctx.islandId())
            .map(lvl -> List.of(GuiButton.nonItalic("Island Level: " + lvl, ChatFormatting.GOLD)))
            .orElse(List.of())
    );

    // 2. Register a declarative PAGED_GRID screen
    core.guis().registerScreen(LEADERBOARD_SCREEN, ctx -> {
        String sort = ctx.stateOrDefault("sort", "level");
        GuiScreenModel.Builder builder = GuiScreenModel.paged("Island Top (" + sort + ")")
            .contextButton(GuiButton.builder("minecraft:hopper", "Sort: " + sort, ChatFormatting.GOLD)
                .lore(GuiButton.nonItalic("Click to cycle sort order", ChatFormatting.GRAY))
                .onClick(click -> {
                    String next = "level".equals(click.stateOrDefault("sort", "level")) ? "recent" : "level";
                    click.refreshWithState("sort", next);
                })
                .build())
            .emptyStateButton(GuiButton.of("minecraft:barrier", "No Ranked Islands Yet", ChatFormatting.RED));

        for (TopEntry entry : levelService.topEntries(sort)) {
            builder.addPagedEntry(
                GuiButton.playerHead(entry.ownerUuid(), "#" + entry.rank() + " " + entry.ownerName(), ChatFormatting.AQUA)
                    .lore(
                        GuiButton.nonItalic("Level: " + entry.level(), ChatFormatting.YELLOW),
                        GuiButton.nonItalic("Click to inspect details", ChatFormatting.GRAY)
                    )
                    .onClick(click -> click.openScreen(DETAIL_SCREEN, entry.islandId()))
                    .build()
            );
        }
        return builder.build();
    });

    // 3. Expose an Actions-tab entry in /is panel that opens the screen cleanly
    core.actions().register(new IslandActionDescriptor(
        Identifier.fromNamespaceAndPath("ogsky_level", "open_top"),
        "Island Top",
        "View ranked Skyblock islands",
        "minecraft:experience_bottle",
        30,
        false, // Keep false so the panel hands off cleanly to the new sub-screen
        actionCtx -> {
            if (!core.guis().openScreen(actionCtx.actor(), LEADERBOARD_SCREEN, actionCtx.islandId())) {
                actionCtx.actor().sendSystemMessage(Component.literal("GUI panel module is not installed; use /is top."));
            }
        }
    ));
}
```

> When a module screen is opened while `/is panel` (`OgskyPanelMenu`) is open, `PanelGuiHost` automatically attaches a back-request to `GuiService.MAIN_PANEL_SCREEN_ID` preserving the player's current tab, `flagMode`, and page. Clicking slot `45` (`Back to Control Panel`) returns directly to that state.

### Command Fallback When `ogsky-panel` Is Absent

Commands such as `/is top` must work even when the server is built with `-Pmodules=core,level` (no `ogsky-panel`):

```java
if (core.guis().openScreen(player, LEADERBOARD_SCREEN, islandId)) {
    return 1;
}
// Chat fallback when ogsky-panel is not bundled (core.guis().isAvailable() == false)
sendChatLeaderboard(player);
return 1;
```

---

## Security & Lifecycle Guarantees (`ogsky-panel`)

- **Shared Anti-Dupe Renderer:** Both `OgskyPanelMenu` and `OgskyCustomGuiMenu` extend `OgskyReadOnlyChestMenu` (`ReadOnlySlot`, `ogsky_panel_item` NBT marker, cursor clearing, offhand/hotbar resync, and `ServerGamePacketListenerImplMixin` blocking creative slot injection and offhand swaps).
- **Live Refresh & Deletion Safety:** Modules can call `core.guis().refreshIslandScreens(islandId)` or `core.guis().refreshScreen(player, screenId)` after async data finishes loading. `PanelGuiHost` automatically closes open island sub-screens if the viewed island is deleted (`IslandDeletedEvent`) or if the viewer becomes banned.
- **Two-Click Confirmation:** Attaching `.confirm("target_key", "Click again to confirm")` (or `GuiConfirmSpec.of(...)`) to any `GuiButton` enforces a 10-second confirmation click and appends the prompt line dynamically before invoking `onClick`.
