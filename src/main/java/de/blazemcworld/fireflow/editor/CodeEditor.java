package de.blazemcworld.fireflow.editor;

import de.blazemcworld.fireflow.FireFlow;
import de.blazemcworld.fireflow.editor.action.DeleteSelectionAction;
import de.blazemcworld.fireflow.editor.action.MoveSelectionAction;
import de.blazemcworld.fireflow.editor.widget.NodeCategoryWidget;
import de.blazemcworld.fireflow.editor.widget.NodeInputWidget;
import de.blazemcworld.fireflow.editor.widget.NodeWidget;
import de.blazemcworld.fireflow.editor.widget.WireWidget;
import de.blazemcworld.fireflow.node.Node;
import de.blazemcworld.fireflow.node.NodeCategory;
import de.blazemcworld.fireflow.node.NodeList;
import de.blazemcworld.fireflow.space.Space;
import de.blazemcworld.fireflow.util.PlayerExitInstanceEvent;
import de.blazemcworld.fireflow.value.SignalValue;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.other.InteractionMeta;
import net.minestom.server.event.EventNode;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.*;
import net.minestom.server.event.trait.InstanceEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.network.NetworkBuffer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

public class CodeEditor {

    public final InstanceContainer inst;
    public final List<Widget> widgets = new ArrayList<>();
    private final HashMap<Player, EditorAction> actions = new HashMap<>();
    private final Path filePath;

    public CodeEditor(Space space) {
        filePath = Path.of("spaces").resolve(String.valueOf(space.info.id)).resolve("code.bin");
        inst = space.code;
        load();

        EventNode<InstanceEvent> events = inst.eventNode();

        events.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            player.setAllowFlying(true);
            player.setFlying(true);

            Entity helper = new Entity(EntityType.INTERACTION);
            helper.setNoGravity(true);
            InteractionMeta meta = (InteractionMeta) helper.getEntityMeta();
            meta.setWidth(-0.5f);
            meta.setHeight(-0.5f);
            helper.setInstance(inst, Pos.ZERO);
            player.addPassenger(helper);
        });

        events.addListener(PlayerExitInstanceEvent.class, event -> {
            for (Entity passenger : event.getPlayer().getPassengers()) {
                if (passenger.getEntityType() == EntityType.INTERACTION) {
                    passenger.remove();
                }
            }
            if (actions.containsKey(event.getPlayer())) {
                setAction(event.getPlayer(), null);
            }
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            Vec cursor = getCursor(event.getPlayer());
            if (actions.containsKey(event.getPlayer())) {
                actions.get(event.getPlayer()).rightClick(cursor);
                return;
            }
            Widget selected = getWidget(event.getPlayer(), cursor);
            if (selected == null) {
                this.setAction(event.getPlayer(), new MoveSelectionAction(inst, cursor, event.getPlayer(), this));
                return;
            }
            selected.rightClick(cursor, event.getPlayer(), this);
        });

        events.addListener(PlayerSwapItemEvent.class, event -> {
            Vec cursor = getCursor(event.getPlayer());
            if (actions.containsKey(event.getPlayer())) {
                actions.get(event.getPlayer()).swapItem(cursor);
                return;
            }
            Widget selected = getWidget(event.getPlayer(), cursor);
            if (selected == null) {
                widgets.add(new NodeCategoryWidget(cursor, inst, NodeCategory.ROOT));
                return;
            }
            selected.swapItem(cursor, event.getPlayer(), this);
        });

        events.addListener(EntityAttackEvent.class, event -> {
            if (event.getEntity() instanceof Player player) {
                Vec cursor = getCursor(player);
                if (actions.containsKey(player)) {
                    actions.get(player).leftClick(cursor);
                    return;
                }
                Widget selected = getWidget(player, cursor);
                if (selected == null) {
                    this.setAction(player, new DeleteSelectionAction(inst, cursor, player, this));
                    return;
                }
                selected.leftClick(cursor, player, this);
            }
        });

        events.addListener(PlayerTickEvent.class, event -> {
            if (!actions.containsKey(event.getPlayer())) return;
            Vec cursor = getCursor(event.getPlayer());
            actions.get(event.getPlayer()).tick(cursor);
        });

        events.addListener(PlayerChatEvent.class, event -> {
            Vec cursor = getCursor(event.getPlayer());
            if (actions.containsKey(event.getPlayer())) {
                actions.get(event.getPlayer()).chat(cursor, event);
                return;
            }
            Widget selected = getWidget(event.getPlayer(), cursor);
            if (selected == null) return;
            selected.chat(cursor, event, this);
        });
    }

    @Nullable
    public Widget getWidget(Player player, Vec cursor) {
        for (Widget w : widgets) {
            Widget res = w.select(player, cursor);
            if (res != null) return res;
        }
        return null;
    }

    private Vec getCursor(Player player) {
        double norm = player.getPosition().direction().dot(new Vec(0, 0, -1));
        if (norm >= 0) return Vec.ZERO.withZ(15.999);
        Vec start = player.getPosition().asVec().add(0.0, player.getEyeHeight(), -16);
        double dist = -start.dot(new Vec(0, 0, -1)) / norm;
        if (dist < 0) return Vec.ZERO.withZ(15.999);
        Vec out = start.add(player.getPosition().direction().mul(dist)).withZ(15.999);
        if (out.y() > 99999) out = out.withY(99999);
        if (out.y() < -99999) out = out.withY(-99999);
        if (out.x() > 99999) out = out.withX(99999);
        if (out.x() < -99999) out = out.withX(-99999);
        return out;
    }


    public void remove(Widget widget) {
        widget.remove();
        widgets.remove(widget);
    }

    public void setAction(Player player, EditorAction action) {
        if (actions.containsKey(player)) {
            actions.get(player).stop();
        }
        if (action == null) actions.remove(player);
        else actions.put(player, action);
    }

    public void save() {
        NetworkBuffer buffer = new NetworkBuffer();
        buffer.write(NetworkBuffer.INT, 0); // version

        List<NodeWidget> nodes = new ArrayList<>();
        for (Widget w : widgets) {
            if (w instanceof NodeWidget node) nodes.add(node);
        }

        buffer.write(NetworkBuffer.INT, nodes.size());
        for (NodeWidget n : nodes) {
            buffer.write(NetworkBuffer.STRING, n.node.getBaseName());
            n.node.writeData(buffer);
            buffer.write(NetworkBuffer.DOUBLE, n.origin.x());
            buffer.write(NetworkBuffer.DOUBLE, n.origin.y());

            buffer.write(NetworkBuffer.INT, n.inputs.size());
            for (NodeInputWidget i : n.inputs) {
                buffer.write(NetworkBuffer.INT, i.wires.size());
                for (WireWidget wire : i.wires) {
                    buffer.write(NetworkBuffer.INT, nodes.indexOf(wire.output.parent));
                    buffer.write(NetworkBuffer.INT, wire.output.parent.outputs.indexOf(wire.output));

                    buffer.write(NetworkBuffer.INT, wire.relays.size());
                    for (Vec relay : wire.relays) {
                        buffer.write(NetworkBuffer.DOUBLE, relay.x());
                        buffer.write(NetworkBuffer.DOUBLE, relay.y());
                    }
                }
            }
        }

        try {
            if (!Files.exists(filePath.getParent())) Files.createDirectories(filePath.getParent());
            Files.write(filePath, buffer.readBytes(buffer.writeIndex()));
        } catch (IOException err) {
            FireFlow.LOGGER.error("Failed to save code file!", err);
        }
    }

    public void load() {
        for (Widget w : widgets) {
            w.remove();
        }
        widgets.clear();

        NetworkBuffer buffer;
        try {
            if (!Files.exists(filePath)) return;
            buffer = new NetworkBuffer(ByteBuffer.wrap(Files.readAllBytes(filePath)));
        } catch (IOException err) {
            FireFlow.LOGGER.error("Failed to read code file!", err);
            return;
        }

        int version = buffer.read(NetworkBuffer.INT);

        List<Runnable> connectNodes = new ArrayList<>();

        int nodeCount = buffer.read(NetworkBuffer.INT);
        for (int nodeId = 0; nodeId < nodeCount; nodeId++) {
            String id = buffer.read(NetworkBuffer.STRING);
            Supplier<Node> supplier = NodeList.nodes.get(id);
            if (supplier == null) {
                widgets.add(null);
                return;
            }
            Node node = supplier.get();
            node = node.readData(buffer);
            double x = buffer.read(NetworkBuffer.DOUBLE);
            double y = buffer.read(NetworkBuffer.DOUBLE);

            NodeWidget widget = new NodeWidget(new Vec(x, y, 15.999), inst, node);
            widget.update(false);

            int inputCount = buffer.read(NetworkBuffer.INT);
            for (int inputId = 0; inputId < inputCount; inputId++) {

                int wireCount = buffer.read(NetworkBuffer.INT);
                for (int wireId = 0; wireId < wireCount; wireId++) {
                    int outNode = buffer.read(NetworkBuffer.INT);
                    int output = buffer.read(NetworkBuffer.INT);

                    int relayCount = buffer.read(NetworkBuffer.INT);
                    List<Vec> relays = new ArrayList<>();
                    for (int l = 0; l < relayCount; l++) {
                        relays.add(new Vec(buffer.read(NetworkBuffer.DOUBLE), buffer.read(NetworkBuffer.DOUBLE), 15.999));
                    }

                    int currentInputId = inputId;
                    connectNodes.add(() -> {
                        if (widgets.get(outNode) instanceof NodeWidget out) {
                            widget.inputs.get(currentInputId).addWire(new WireWidget(
                                    inst, widget.inputs.get(currentInputId), out.outputs.get(output), relays
                            ));
                            if (widget.node.inputs.get(currentInputId).type == SignalValue.INSTANCE) {
                                out.node.outputs.get(output).connectSignal(widget.node.inputs.get(currentInputId));
                            } else {
                                widget.node.inputs.get(currentInputId).connectValue(out.node.outputs.get(output));
                            }
                        }
                    });
                }
            }

            widgets.add(widget);
        }

        for (Runnable r : connectNodes) r.run();
    }

    public List<Node> getNodes() {
        List<Node> list = new ArrayList<>();
        for (Widget w : widgets) {
            if (w instanceof NodeWidget node) list.add(node.node);
        }
        return list;
    }

    public List<NodeWidget> getNodesInBound(Bounds bounds) {
        List<NodeWidget> list = new ArrayList<>();
        for (Widget w : widgets) {
            if (w instanceof NodeWidget node) {
                Bounds nodeBounds = node.getBounds();
                if (bounds.includes2d(nodeBounds.min) && bounds.includes2d(nodeBounds.max)) list.add(node);
            }
        }
        return list;
    }
}
