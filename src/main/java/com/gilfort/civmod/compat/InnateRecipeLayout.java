package com.gilfort.civmod.compat;

/**
 * The layout of the display-only "Job Recipes" category shared by JEI and REI (vault {@code Systems/Producing Jobs.md}):
 * the inputs in a 3×3 grid, an arrow, the result; below them the tool and the station; at the bottom the icons of the
 * Jobs that know the recipe. Positions are relative to the category's top left corner, in pixels.
 */
public final class InnateRecipeLayout {

    /** Width of the category. */
    public static final int WIDTH = 116;
    /** Height of the category. */
    public static final int HEIGHT = 76;
    /** One slot. */
    public static final int SLOT = 18;
    /** The arrow between inputs and result. */
    public static final int ARROW_X = 61;
    /** The result slot. */
    public static final int OUTPUT_X = 95;
    /** The row of the arrow and the result: the middle input row. */
    public static final int MIDDLE_Y = 19;
    /** The tool slot. */
    public static final int TOOL_X = 59;
    /** The station slot. */
    public static final int STATION_X = 77;
    /** The row of the tool and the station. */
    public static final int EXTRAS_Y = 38;
    /** The row of the Job icons. */
    public static final int JOBS_Y = 58;
    /** How many Job icons fit into the row. */
    public static final int MAX_JOBS = WIDTH / SLOT;

    /** Title of the category ("Job Recipes"). */
    public static final String TITLE = "civmod.recipe_viewer.innate_recipes";
    /** Tooltip line of the tool slot. */
    public static final String TOOL_LINE = TITLE + ".tool";
    /** Tooltip line of the station slot. */
    public static final String STATION_LINE = TITLE + ".station";

    private InnateRecipeLayout() {
    }

    /**
     * @param index an input's index
     * @return the x of its slot in the 3×3 grid
     */
    public static int inputX(int index) {
        return 1 + (index % 3) * SLOT;
    }

    /**
     * @param index an input's index
     * @return the y of its slot in the 3×3 grid
     */
    public static int inputY(int index) {
        return 1 + (index / 3) * SLOT;
    }

    /**
     * @param index a Job icon's index
     * @return the x of its slot in the Job row
     */
    public static int jobX(int index) {
        return 1 + index * SLOT;
    }
}
