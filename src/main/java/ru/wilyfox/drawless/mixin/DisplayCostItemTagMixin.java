package ru.wilyfox.drawless.mixin;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import ru.wilyfox.drawless.diagnostics.DisplayCostProbe;
import java.util.List;

@Mixin(SubmitNodeCollection.class)
public class DisplayCostItemTagMixin {
    @Redirect(method="submitItem",at=@At(value="NEW",target="net/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit"))
    private ItemFeatureRenderer.Submit drawless$tagItem(PoseStack.Pose pose,ItemDisplayContext context,int light,int overlay,int outline,int[] tints,List<BakedQuad> quads,ItemStackRenderState.FoilType foil){
        var submit=new ItemFeatureRenderer.Submit(pose,context,light,overlay,outline,tints,quads,foil);
        DisplayCostProbe.tagged(submit);return submit;
    }
}
