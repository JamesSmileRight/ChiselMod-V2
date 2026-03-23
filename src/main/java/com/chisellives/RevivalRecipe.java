package com.chisellives;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

public final class RevivalRecipe {

    private final JavaPlugin plugin;
    private final RevivalTotemManager revivalTotemManager;
    private final NamespacedKey recipeKey;

    public RevivalRecipe(JavaPlugin plugin, RevivalTotemManager revivalTotemManager) {
        this.plugin = plugin;
        this.revivalTotemManager = revivalTotemManager;
        this.recipeKey = new NamespacedKey(plugin, "revival_totem_recipe");
    }

    public void register() {
        Bukkit.removeRecipe(recipeKey);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, revivalTotemManager.createRevivalTotemItem());
        recipe.shape("BHB", "NTN", "BHB");
        recipe.setIngredient('B', Material.BEACON);
        recipe.setIngredient('H', Material.PIGLIN_HEAD);
        recipe.setIngredient('N', Material.NETHERITE_BLOCK);
        recipe.setIngredient('T', Material.TOTEM_OF_UNDYING);

        boolean added = Bukkit.addRecipe(recipe);
        if (!added) {
            plugin.getLogger().warning("[ChiselLives] Failed to register Revival Totem recipe.");
        }
    }

    public void unregister() {
        Bukkit.removeRecipe(recipeKey);
    }
}
