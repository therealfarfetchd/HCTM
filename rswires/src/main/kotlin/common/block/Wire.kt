package therealfarfetchd.rswires.common.block

import net.minecraft.block.Block
import net.minecraft.block.BlockState
import net.minecraft.block.Blocks
import net.minecraft.block.Material
import net.minecraft.nbt.ByteTag
import net.minecraft.nbt.Tag
import net.minecraft.server.world.ServerWorld
import net.minecraft.state.StateManager.Builder
import net.minecraft.state.property.Properties
import net.minecraft.util.DyeColor
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.world.BlockView
import net.minecraft.world.World
import net.minecraft.world.dimension.DimensionType
import therealfarfetchd.hctm.common.block.BaseWireBlock
import therealfarfetchd.hctm.common.block.BaseWireBlockEntity
import therealfarfetchd.hctm.common.block.BaseWireProperties
import therealfarfetchd.hctm.common.block.SingleBaseWireBlock
import therealfarfetchd.hctm.common.block.WireUtils
import therealfarfetchd.hctm.common.wire.ConnectionDiscoverers
import therealfarfetchd.hctm.common.wire.ConnectionFilter
import therealfarfetchd.hctm.common.wire.NetNode
import therealfarfetchd.hctm.common.wire.Network
import therealfarfetchd.hctm.common.wire.NodeView
import therealfarfetchd.hctm.common.wire.PartExt
import therealfarfetchd.hctm.common.wire.WirePartExtType
import therealfarfetchd.hctm.common.wire.find
import therealfarfetchd.hctm.common.wire.getWireNetworkState
import therealfarfetchd.rswires.RSWires
import therealfarfetchd.rswires.common.init.BlockEntityTypes
import java.util.*
import kotlin.experimental.and
import kotlin.experimental.or

class RedAlloyWireBlock : SingleBaseWireBlock(Block.Settings.of(Material.STONE).noCollision().strength(0.25f, 0.25f), 2 / 16f) {

  init {
    defaultState = defaultState.with(WireProperties.Powered, false)
  }

  override fun appendProperties(b: Builder<Block, BlockState>) {
    super.appendProperties(b)
    b.add(WireProperties.Powered)
  }

  override fun emitsRedstonePower(state: BlockState?): Boolean {
    return true
  }

  override fun getStrongRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.Powered] &&
      state[BaseWireProperties.PlacedWires[facing]] &&
      view.getBlockState(pos.offset(facing)).block != Blocks.REDSTONE_WIRE
    ) 15 else 0
  }

  override fun getWeakRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.Powered] &&
      (BaseWireProperties.PlacedWires - facing.opposite).any { state[it.value] } &&
      view.getBlockState(pos.offset(facing.opposite)).block != Blocks.REDSTONE_WIRE
    ) 15 else 0
  }

  override fun neighborUpdate(state: BlockState, world: World, pos: BlockPos, block: Block, neighborPos: BlockPos, moved: Boolean) {
    if (world is ServerWorld) {
      RSWires.wiresGivePower = false
      if (isReceivingPower(state, world, pos) != state[WireProperties.Powered]) {
        RedstoneWireUtils.scheduleUpdate(world, pos)
      }
      RSWires.wiresGivePower = true
    }
  }

  override fun createPartExtFromSide(side: Direction) = RedAlloyWirePartExt(side)

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.RedAlloyWire)

  companion object {
    fun isReceivingPower(state: BlockState, world: World, pos: BlockPos): Boolean {
      val sides = Direction.values().filter { state[BaseWireProperties.PlacedWires[it]] }
      val weakSides = Direction.values().filter { a -> sides.any { b -> b.axis != a.axis } }.distinct() - sides
      return sides.any { world.getReceivedRedstonePower(pos.offset(it)) > 0 } || weakSides.any { world.getEmittedRedstonePower(pos.offset(it), it) > 0 }
    }
  }

}

class InsulatedWireBlock(val color: DyeColor) : SingleBaseWireBlock(Block.Settings.of(Material.STONE).noCollision().strength(0.25f, 0.25f), 3 / 16f) {

  init {
    defaultState = defaultState.with(WireProperties.Powered, false)
  }

  override fun appendProperties(b: Builder<Block, BlockState>) {
    super.appendProperties(b)
    b.add(WireProperties.Powered)
  }

  override fun createPartExtFromSide(side: Direction) = InsulatedWirePartExt(side, color)

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.InsulatedWire)

  override fun getStrongRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return 0
  }

  override fun getWeakRedstonePower(state: BlockState, view: BlockView, pos: BlockPos, facing: Direction): Int {
    return if (
      RSWires.wiresGivePower &&
      state[WireProperties.Powered] &&
      state[BaseWireProperties.PlacedWires[facing.opposite]] &&
      view.getBlockState(pos.offset(facing.opposite)).block != Blocks.REDSTONE_WIRE
    ) 15 else 0
  }

  override fun neighborUpdate(state: BlockState, world: World, pos: BlockPos, block: Block, neighborPos: BlockPos, moved: Boolean) {
    if (world is ServerWorld) {
      RSWires.wiresGivePower = false
      if (RedAlloyWireBlock.isReceivingPower(state, world, pos) != state[WireProperties.Powered]) {
        RedstoneWireUtils.scheduleUpdate(world, pos)
      }
      RSWires.wiresGivePower = true
    }
  }

  override fun onBlockAdded(state: BlockState, world: World, pos: BlockPos, oldState: BlockState, moved: Boolean) {
    if (world is ServerWorld) {
      RedstoneWireUtils.scheduleUpdate(world, pos)
    }
  }

  companion object {
    fun isReceivingPower(state: BlockState, world: World, pos: BlockPos): Boolean {
      val sides = Direction.values().filter { state[BaseWireProperties.PlacedWires[it]] }
      val weakSides = Direction.values().filter { a -> sides.any { b -> b.axis != a.axis } }.distinct() - sides
      return weakSides.any { world.getEmittedRedstonePower(pos.offset(it), it) > 0 }
    }
  }

}

class BundledCableBlock(val color: DyeColor?) : BaseWireBlock(Block.Settings.of(Material.STONE).noCollision().strength(0.25f, 0.25f), 4 / 16f) {

  override fun createExtFromTag(tag: Tag): PartExt? {
    val data = (tag as? ByteTag)?.byte ?: return null
    val inner = DyeColor.byId(data.toInt() shr 4 and 15)
    val dir = data and 15
    return if (dir in 0 until 6) BundledCablePartExt(Direction.byId(dir.toInt()), color, inner)
    else null
  }

  override fun createPartExtsFromSide(side: Direction): Set<PartExt> {
    return DyeColor.values().map { BundledCablePartExt(side, color, it) }.toSet()
  }

  override fun createBlockEntity(view: BlockView) = BaseWireBlockEntity(BlockEntityTypes.BundledCable)

}

data class RedAlloyWirePartExt(override val side: Direction) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.RedAlloy

  override fun getState(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    return world.getBlockState(pos)[WireProperties.Powered]
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {
    val pos = self.data.pos
    world.setBlockState(pos, world.getBlockState(pos).with(WireProperties.Powered, state))
  }

  override fun getInput(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    return RedAlloyWireBlock.isReceivingPower(world.getBlockState(pos), world, pos)
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.Wire, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte())
  }
}

data class InsulatedWirePartExt(override val side: Direction, val color: DyeColor) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.Colored(color)

  override fun getState(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    return world.getBlockState(pos)[WireProperties.Powered]
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {
    val pos = self.data.pos
    world.setBlockState(pos, world.getBlockState(pos).with(WireProperties.Powered, state))
  }

  override fun getInput(world: World, self: NetNode): Boolean {
    val pos = self.data.pos
    return InsulatedWireBlock.isReceivingPower(world.getBlockState(pos), world, pos)
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.Wire, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte())
  }
}

data class BundledCablePartExt(override val side: Direction, val color: DyeColor?, val inner: DyeColor) : PartExt, WirePartExtType, PartRedstoneCarrier {
  override val type = RedstoneWireType.Bundled(color, inner)

  override fun getState(world: World, self: NetNode): Boolean {
    return false
  }

  override fun setState(world: World, self: NetNode, state: Boolean) {}

  override fun getInput(world: World, self: NetNode): Boolean {
    return false
  }

  override fun tryConnect(self: NetNode, world: ServerWorld, pos: BlockPos, nv: NodeView): Set<NetNode> {
    return find(ConnectionDiscoverers.Wire, RedstoneCarrierFilter, self, world, pos, nv)
  }

  override fun onChanged(self: NetNode, world: ServerWorld, pos: BlockPos) {
    RedstoneWireUtils.scheduleUpdate(world, pos)
    WireUtils.updateClient(world, pos)
  }

  override fun toTag(): Tag {
    return ByteTag.of(side.id.toByte() or (inner.id shl 4).toByte())
  }
}

interface PartRedstoneCarrier : PartExt {
  val type: RedstoneWireType

  fun getState(world: World, self: NetNode): Boolean

  fun setState(world: World, self: NetNode, state: Boolean)

  fun getInput(world: World, self: NetNode): Boolean
}

sealed class RedstoneWireType {
  object RedAlloy : RedstoneWireType()
  data class Colored(val color: DyeColor) : RedstoneWireType()
  data class Bundled(val color: DyeColor?, val inner: DyeColor) : RedstoneWireType()

  fun canConnect(other: RedstoneWireType): Boolean {
    if (this == other) return true
    if (this == RedAlloy && other is Colored || this is Colored && other == RedAlloy) return true
    if (this is Colored && other is Bundled && other.inner == this.color || this is Bundled && other is Colored && this.inner == other.color) return true
    if (other is Bundled && this == Bundled(null, other.inner) || this is Bundled && other == Bundled(null, this.inner)) return true
    return false
  }
}

object RedstoneCarrierFilter : ConnectionFilter {
  override fun accepts(self: NetNode, other: NetNode): Boolean {
    val d1 = self.data.ext as? PartRedstoneCarrier ?: return false
    val d2 = other.data.ext as? PartRedstoneCarrier ?: return false
    return d1.type.canConnect(d2.type)
  }
}

object WireProperties {
  val Powered = Properties.POWERED
}

object RedstoneWireUtils {

  var scheduled = mapOf<DimensionType, Set<UUID>>()

  fun scheduleUpdate(world: ServerWorld, pos: BlockPos) {
    scheduled += world.dimension.type to scheduled[world.dimension.type].orEmpty() + world.getWireNetworkState().controller.getNetworksAt(pos).map { it.id }
  }

  fun flushUpdates(world: ServerWorld) {
    val wireNetworkState = world.getWireNetworkState()
    for (id in scheduled[world.dimension.type].orEmpty()) {
      val net = wireNetworkState.controller.getNetwork(id)
      if (net != null) updateState(world, net)
    }
    scheduled -= world.dimension.type
  }

  fun updateState(world: World, network: Network) {
    val isOn = try {
      RSWires.wiresGivePower = false
      network.getNodes().any { (it.data.ext as PartRedstoneCarrier).getInput(world, it) }
    } finally {
      RSWires.wiresGivePower = true
    }
    for (node in network.getNodes()) {
      val ext = node.data.ext as PartRedstoneCarrier
      ext.setState(world, node, isOn)
    }
  }

}