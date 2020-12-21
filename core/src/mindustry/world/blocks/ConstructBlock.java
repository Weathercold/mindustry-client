package mindustry.world.blocks;

import arc.*;
import arc.Graphics.*;
import arc.Graphics.Cursor.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.ui.Label;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import mindustry.annotations.Annotations.*;
import mindustry.client.Client;
import mindustry.client.antigreif.*;
import mindustry.client.navigation.Navigation;
import mindustry.client.navigation.UnAssistPath;
import mindustry.client.ui.Toast;
import mindustry.content.*;
import mindustry.core.World;
import mindustry.entities.*;
import mindustry.entities.units.*;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.world.*;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.storage.CoreBlock.*;
import mindustry.world.modules.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;

import static mindustry.Vars.*;
import static mindustry.ui.Styles.monoLabel;

/** A block in the process of construction. */
public class ConstructBlock extends Block{
    private static final ConstructBlock[] consBlocks = new ConstructBlock[maxBlockSize];

    private static long lastWarn;
    private static long lastTime = 0;
    private static int pitchSeq = 0;
    private static long lastPlayed;

    public ConstructBlock(int size){
        super("build" + size);
        this.size = size;
        update = true;
        health = 20;
        consumesTap = true;
        solidifes = true;
        consBlocks[size - 1] = this;
        sync = true;
    }

    /** Returns a ConstructBlock by size. */
    public static ConstructBlock get(int size){
        if(size > maxBlockSize) throw new IllegalArgumentException("No. Don't place ConstructBlock of size greater than " + maxBlockSize);
        return consBlocks[size - 1];
    }

    @Remote(called = Loc.server)
    public static void deconstructFinish(Tile tile, Block block, Unit builder){
        if(tile != null && builder != null && block != null){
            tile.getLinkedTiles(t -> t.addToLog(new BreakTileLog(builder, t, Instant.now().getEpochSecond(), "", block)));
            if(Navigation.currentlyFollowing instanceof UnAssistPath){
                if(((UnAssistPath) Navigation.currentlyFollowing).assisting == builder.getPlayer()){
                    if(block.isVisible()) {
                        ((UnAssistPath) Navigation.currentlyFollowing).toUndo.add(new BuildPlan(tile.x, tile.y, tile.build.rotation, block, tile.build.config()));
                    }
                }
            }
        }
        Team team = tile.team();
        Fx.breakBlock.at(tile.drawx(), tile.drawy(), block.size);
        Events.fire(new BlockBuildEndEvent(tile, builder, team, true, null));
        tile.remove();
        if(shouldPlay()) Sounds.breaks.at(tile, calcPitch(false));
    }

    @Remote(called = Loc.server)
    public static void constructFinish(Tile tile, Block block, @Nullable Unit builder, byte rotation, Team team, Object config){
        if(tile == null) return;

        float healthf = tile.build == null ? 1f : tile.build.healthf();
        Seq<Building> prev = tile.build instanceof ConstructBuild ? ((ConstructBuild)tile.build).prevBuild : null;

        tile.setBlock(block, team, rotation);

        if(tile.build != null){
            tile.build.health = block.health * healthf;

            if(config != null){
                tile.build.configured(builder, config);
            }

            if(prev != null && prev.size > 0){
                tile.build.overwrote(prev);
            }

            if(builder != null && builder.isPlayer()){
                tile.build.lastAccessed = builder.getPlayer().name;
            }
        }

        //last builder was this local client player, call placed()
        if(tile.build != null && !headless && builder == player.unit()){
            tile.build.playerPlaced(config);
        }

        Events.fire(new BlockBuildEndEvent(tile, builder, team, false, config));

        Fx.placeBlock.at(tile.drawx(), tile.drawy(), block.size);

        if(builder != null && tile.build != null){
            tile.getLinkedTiles(t -> t.addToLog(new PlaceTileLog(builder, t, Instant.now().getEpochSecond(), "", block, tile.build.config())));
            if(Navigation.currentlyFollowing instanceof UnAssistPath){
                if (((UnAssistPath) Navigation.currentlyFollowing).assisting == builder.getPlayer()) {
                    if(Navigation.currentlyFollowing != null) {
                        for (BuildPlan p : ((UnAssistPath) Navigation.currentlyFollowing).toUndo) {
                            if (p.x == tile.x && p.y == tile.y) {
                                ((UnAssistPath) Navigation.currentlyFollowing).toUndo.remove(p);
                            }
                        }
                        ((UnAssistPath) Navigation.currentlyFollowing).toUndo.add(new BuildPlan(tile.x, tile.y));
                        if (config != null) {
                            Client.configs.add(new ConfigRequest(tile.x, tile.y, null));
                        }
                    }
                }
            }
        }
        if(shouldPlay()) Sounds.place.at(tile, calcPitch(true));
    }

    static boolean shouldPlay(){
        if(Time.timeSinceMillis(lastPlayed) >= 32){
            lastPlayed = Time.millis();
            return true;
        }else{
            return false;
        }
    }

    static float calcPitch(boolean up){
        if(Time.timeSinceMillis(lastTime) < 16 * 30){
            lastTime = Time.millis();
            pitchSeq ++;
            if(pitchSeq > 30){
                pitchSeq = 0;
            }
            return 1f + Mathf.clamp(pitchSeq / 30f) * (up ? 1.9f : -0.4f);
        }else{
            pitchSeq = 0;
            lastTime = Time.millis();
            return Mathf.random(0.7f, 1.3f);
        }
    }

    public static void constructed(Tile tile, Block block, Unit builder, byte rotation, Team team, Object config){
        Call.constructFinish(tile, block, builder, rotation, team, config);
        if(tile.build != null){
            tile.build.placed();
        }

        Events.fire(new BlockBuildEndEvent(tile, builder, team, false, config));
    }

    @Override
    public boolean isHidden(){
        return true;
    }

    public class ConstructBuild extends Building{
        /**
         * The recipe of the block that is being constructed.
         * If there is no recipe for this block, as is the case with rocks, 'previous' is used.
         */
        public @Nullable Block cblock;
        public @Nullable Seq<Building> prevBuild;

        public float progress = 0;
        public float buildCost;
        /**
         * The block that used to be here.
         * If a non-recipe block is being deconstructed, this is the block that is being deconstructed.
         */
        public Block previous;
        public Object lastConfig;
        public boolean wasConstructing;

        @Nullable
        public Unit lastBuilder;

        private float[] accumulator;
        private float[] totalAccumulator;

        private float lastProgress = 0f;
        private Toast toast = null;

        @Override
        public String getDisplayName(){
            return Core.bundle.format("block.constructing", cblock == null ? previous.localizedName : cblock.localizedName);
        }

        @Override
        public TextureRegion getDisplayIcon(){
            return (cblock == null ? previous : cblock).icon(Cicon.full);
        }

        @Override
        public boolean checkSolid(){
            return (cblock != null && cblock.solid) || previous == null || previous.solid;
        }

        @Override
        public Cursor getCursor(){
            return SystemCursor.hand;
        }

        @Override
        public void tapped(){
            //if the target is constructable, begin constructing
            if(cblock != null){
                if(control.input.buildWasAutoPaused && !control.input.isBuilding && player.isBuilder()){
                    control.input.isBuilding = true;
                }
                player.unit().addBuild(new BuildPlan(tile.x, tile.y, rotation, cblock, lastConfig), false);
            }
        }

        @Override
        public void onDestroyed(){
            Fx.blockExplosionSmoke.at(tile);

            if(!tile.floor().solid && tile.floor().hasSurface()){
                Effect.rubble(x, y, size);
            }
        }

        @Override
        public void draw(){
            if(!(previous == null || cblock == null || previous == cblock) && Core.atlas.isFound(previous.icon(Cicon.full))){
                Draw.rect(previous.icon(Cicon.full), x, y, previous.rotate ? rotdeg() : 0);
            }

            Draw.draw(Layer.blockBuilding, () -> {
                Shaders.blockbuild.color = Pal.accent;

                Block target = cblock == null ? previous : cblock;

                if(target != null){
                    for(TextureRegion region : target.getGeneratedIcons()){
                        Shaders.blockbuild.region = region;
                        Shaders.blockbuild.progress = progress;

                        Draw.rect(region, x, y, target.rotate ? rotdeg() : 0);
                        Draw.flush();
                    }
                }
            });
        }

        public void construct(Unit builder, @Nullable Building core, float amount, Object config){
            wasConstructing = true;
            if(cblock == null){
                kill();
                return;
            }

            if(builder.isPlayer()){
                lastBuilder = builder;
            }

            lastConfig = config;

            if(cblock.requirements.length != accumulator.length || totalAccumulator.length != cblock.requirements.length){
                setConstruct(previous, cblock);
            }

            float maxProgress = core == null || team.rules().infiniteResources ? amount : checkRequired(core.items, amount, false);

            for(int i = 0; i < cblock.requirements.length; i++){
                int reqamount = Math.round(state.rules.buildCostMultiplier * cblock.requirements[i].amount);
                accumulator[i] += Math.min(reqamount * maxProgress, reqamount - totalAccumulator[i] + 0.00001f); //add min amount progressed to the accumulator
                totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * maxProgress, reqamount);
            }

            maxProgress = core == null || team.rules().infiniteResources ? maxProgress : checkRequired(core.items, maxProgress, true);

            progress = Mathf.clamp(progress + maxProgress);

            if(progress >= 1f || state.rules.infiniteResources){
                if(lastBuilder == null) lastBuilder = builder;
                constructed(tile, cblock, lastBuilder, (byte)rotation, builder.team, config);
            }
        }

        public void deconstruct(Unit builder, @Nullable Building core, float amount){
            wasConstructing = false;
            /* TODO: Look into this
            tile.getLinkedTiles(t -> t.addToLog(new BreakTileLog(builder, t, Instant.now().getEpochSecond(), "", this.cblock == null ? previous : this.cblock))); */
            float deconstructMultiplier = state.rules.deconstructRefundMultiplier;

            if(builder.isPlayer()){
                lastBuilder = builder;
            }

            if(cblock != null){
                ItemStack[] requirements = cblock.requirements;
                if(requirements.length != accumulator.length || totalAccumulator.length != requirements.length){
                    setDeconstruct(cblock);
                }

                //make sure you take into account that you can't deconstruct more than there is deconstructed
                float clampedAmount = Math.min(amount, progress);

                for(int i = 0; i < requirements.length; i++){
                    int reqamount = Math.round(state.rules.buildCostMultiplier * requirements[i].amount);
                    accumulator[i] += Math.min(clampedAmount * deconstructMultiplier * reqamount, deconstructMultiplier * reqamount - totalAccumulator[i]); //add scaled amount progressed to the accumulator
                    totalAccumulator[i] = Math.min(totalAccumulator[i] + reqamount * clampedAmount * deconstructMultiplier, reqamount);

                    int accumulated = (int)(accumulator[i]); //get amount

                    if(clampedAmount > 0 && accumulated > 0){ //if it's positive, add it to the core
                        if(core != null && requirements[i].item.unlockedNow()){ //only accept items that are unlocked
                            int accepting = Math.min(accumulated, ((CoreBuild)core).storageCapacity - core.items.get(requirements[i].item));
                            //transfer items directly, as this is not production.
                            core.items.add(requirements[i].item, accepting);
                            accumulator[i] -= accepting;
                        }else{
                            accumulator[i] -= accumulated;
                        }
                    }
                }
            }

            progress = Mathf.clamp(progress - amount);

            if(progress <= (previous == null ? 0 : previous.deconstructThreshold) || state.rules.infiniteResources){
                if(lastBuilder == null) lastBuilder = builder;
                Call.deconstructFinish(tile, this.cblock == null ? previous : this.cblock, lastBuilder);
            }
        }

        private float checkRequired(ItemModule inventory, float amount, boolean remove){
            float maxProgress = amount;

            for(int i = 0; i < cblock.requirements.length; i++){
                int sclamount = Math.round(state.rules.buildCostMultiplier * cblock.requirements[i].amount);
                int required = (int)(accumulator[i]); //calculate items that are required now

                if(inventory.get(cblock.requirements[i].item) == 0 && sclamount != 0){
                    maxProgress = 0f;
                }else if(required > 0){ //if this amount is positive...
                    //calculate how many items it can actually use
                    int maxUse = Math.min(required, inventory.get(cblock.requirements[i].item));
                    //get this as a fraction
                    float fraction = maxUse / (float)required;

                    //move max progress down if this fraction is less than 1
                    maxProgress = Math.min(maxProgress, maxProgress * fraction);

                    accumulator[i] -= maxUse;

                    //remove stuff that is actually used
                    if(remove){
                        inventory.remove(cblock.requirements[i].item, maxUse);
                    }
                }
                //else, no items are required yet, so just keep going
            }

            return maxProgress;
        }

        public float progress(){
            return progress;
        }

        public void setConstruct(Block previous, Block block){
            wasConstructing = true;
            this.cblock = block;
            this.previous = previous;
            this.accumulator = new float[block.requirements.length];
            this.totalAccumulator = new float[block.requirements.length];
            this.buildCost = block.buildCost * state.rules.buildCostMultiplier;
        }

        public void setDeconstruct(Block previous){
            if(previous == null) return;
            wasConstructing = false;
            this.previous = previous;
            this.progress = 1f;
            if(previous.buildCost >= 0.01f){
                this.cblock = previous;
                this.buildCost = previous.buildCost * state.rules.buildCostMultiplier;
            }else{
                this.buildCost = 20f; //default no-requirement build cost is 20
            }
            this.accumulator = new float[previous.requirements.length];
            this.totalAccumulator = new float[previous.requirements.length];
        }

        @Override
        public void write(Writes write){
            super.write(write);
            write.f(progress);
            write.s(previous == null ? -1 : previous.id);
            write.s(cblock == null ? -1 : cblock.id);

            if(accumulator == null){
                write.b(-1);
            }else{
                write.b(accumulator.length);
                for(int i = 0; i < accumulator.length; i++){
                    write.f(accumulator[i]);
                    write.f(totalAccumulator[i]);
                }
            }
        }

        @Override
        public void read(Reads read, byte revision){
            super.read(read, revision);
            progress = read.f();
            short pid = read.s();
            short rid = read.s();
            byte acsize = read.b();

            if(acsize != -1){
                accumulator = new float[acsize];
                totalAccumulator = new float[acsize];
                for(int i = 0; i < acsize; i++){
                    accumulator[i] = read.f();
                    totalAccumulator[i] = read.f();
                }
            }

            if(pid != -1) previous = content.block(pid);
            if(rid != -1) cblock = content.block(rid);

            if(cblock != null){
                buildCost = cblock.buildCost * state.rules.buildCostMultiplier;
            }else{
                buildCost = 20f;
            }
        }

        @Override
        public void update() {
            super.update();
            if (closestCore() == null) return;
            if (cblock instanceof NuclearReactor && team == player.team()) {
                AtomicInteger distance = new AtomicInteger(Integer.MAX_VALUE);
                closestCore().tile.getLinkedTiles(t -> distance.set(Math.min(World.toTile(t.dst(this.tile)) - 1, distance.get())));
                if (Core.settings.getBool("reactorwarnings") && cblock != null && (Core.settings.getInt("reactorwarningdistance") == 0 || distance.intValue() <= Core.settings.getInt("reactorwarningdistance"))) {

                    long since = Time.timeSinceMillis(lastWarn);
                    if (progress > lastProgress && since > 0 && progress < .99f && lastBuilder != null) {
                        // Play sound for reactor construction (only played when no reactor has been built for 10s)
                        if (since > 10 * 1000 && Core.settings.getBool("reactorwarningsounds") && (Core.settings.getInt("reactorsounddistance") == 0 || distance.intValue() <= Core.settings.getInt("reactorsounddistance"))) {
                            Sounds.corexplode.play();
                        }
                        lastWarn = Time.millis();
                        if (lastBuilder.isPlayer()) {
                            lastBuilder.drawBuildRequests();
                            String format = String.format("%s is building a %s at %d,%d (%d block%s from core).", lastBuilder.getPlayer().name, cblock.name, tileX(), tileY(), distance.intValue(), distance.intValue() == 1 ? "" : "s");
                            String format2 = String.format("%02d%% completed.", Mathf.round(progress * 100));
                            if (toast == null || toast.parent == null) {
                                toast = new Toast();
                            } else {
                                toast.clearChildren();
                            }
                            toast.setFadeTime(2f);
                            toast.add(new Label(format));
                            toast.row();
                            toast.add(new Label(format2, monoLabel));
                        }
                    }
                }
            }
            lastProgress = progress;
        }
    }
}
