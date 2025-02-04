/*
 * Copyright (c) 2021-2022 International Innovation Center of Tsinghua University, Shanghai
 * Ventus is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 * See the Mulan PSL v2 for more details. */
package pipeline

import chisel3._
import chisel3.util.{Decoupled, DecoupledIO, MuxLookup, Queue, UIntToOH}
import parameters._
import IDecode._


class CtrlSigs extends Bundle {
  val inst = UInt(32.W)
  val wid = UInt(depth_warp.W)
  val fp = Bool()
  val branch = UInt(2.W)
  val simt_stack = Bool()
  val simt_stack_op = Bool()
  val barrier = Bool()
  val csr = UInt(2.W)
  val reverse = Bool()
  val sel_alu2 = UInt(2.W)
  val sel_alu1 = UInt(2.W)
  val isvec = Bool()
  val sel_alu3 = UInt(2.W)
  val mask=Bool()
  val sel_imm = UInt(3.W)
  val mem_whb = UInt(2.W)
  val mem_unsigned = Bool()
  val alu_fn = UInt(6.W)
  val mem = Bool()
  val mul = Bool()
  val mem_cmd = UInt(2.W)
  val mop = UInt(2.W)
  val reg_idx1 = UInt(5.W)
  val reg_idx2 = UInt(5.W)
  val reg_idx3 = UInt(5.W)
  val reg_idxw = UInt(5.W)
  val wfd = Bool()
  val fence = Bool()
  val sfu = Bool()
  val readmask = Bool()
  val writemask = Bool()
  val wxd = Bool()
  val pc=UInt(32.W)
  //override def cloneType: CtrlSigs.this.type = new CtrlSigs().asInstanceOf[this.type]
}
class scoreboardIO extends Bundle{
  val ibuffer_if_ctrl=Input(new CtrlSigs())
  val if_ctrl=Input(new CtrlSigs())
  val wb_v_ctrl=Input(new WriteVecCtrl())
  val wb_x_ctrl=Input(new WriteScalarCtrl())
  val if_fire=Input(Bool())
  val br_ctrl=Input(Bool())
  val fence_end=Input(Bool())
  val wb_v_fire=Input(Bool())
  val wb_x_fire=Input(Bool())
  val delay=Output(Bool())
}
class ScoreboardUtil(n: Int,zero:Boolean=false)
{
  def set(en: Bool, addr: UInt): Unit = update(en, _next.asUInt() | mask(en, addr))
  def clear(en: Bool, addr: UInt): Unit = update(en, _next.asUInt() & (~mask(en, addr)).asUInt())
  def read(addr: UInt): Bool = r(addr)
  def readBypassed(addr: UInt): Bool = _next(addr)
  private val _r = RegInit(0.U(n.W))
  private val r = if(zero) (_r >> 1 << 1) else _r
  private var _next = r
  private var ens = false.B
  private def mask(en: Bool, addr: UInt) = Mux(en, (1.U << addr).asUInt(), 0.U)
  private def update(en: Bool, update: UInt) = {
    _next = update
    ens = ens || en
    when (ens) { _r := _next }
  }
}
class Scoreboard extends Module{
  val io=IO(new scoreboardIO())
  val vectorReg=new ScoreboardUtil(32)
  val scalarReg=new ScoreboardUtil(32,true)
  val beqReg=new ScoreboardUtil(1)
  val fenceReg=new ScoreboardUtil(1)
  vectorReg.set(io.if_fire & io.if_ctrl.wfd,io.if_ctrl.reg_idxw)
  vectorReg.clear(io.wb_v_fire & io.wb_v_ctrl.wfd,io.wb_v_ctrl.reg_idxw)
  scalarReg.set(io.if_fire & io.if_ctrl.wxd,io.if_ctrl.reg_idxw)
  scalarReg.clear(io.wb_x_fire & io.wb_x_ctrl.wxd,io.wb_x_ctrl.reg_idxw)
  beqReg.set(io.if_fire & ((io.if_ctrl.branch=/=0.U)|(io.if_ctrl.barrier)),0.U)
  beqReg.clear(io.br_ctrl,0.U)
  fenceReg.set(io.if_fire & io.if_ctrl.fence,0.U)
  fenceReg.clear(io.fence_end,0.U)
  val read1=MuxLookup(io.ibuffer_if_ctrl.sel_alu1,false.B,Array(A1_RS1->scalarReg.read(io.ibuffer_if_ctrl.reg_idx1),A1_VRS1->vectorReg.read(io.ibuffer_if_ctrl.reg_idx1)))
  val read2=MuxLookup(io.ibuffer_if_ctrl.sel_alu2,false.B,Array(A2_RS2->scalarReg.read(io.ibuffer_if_ctrl.reg_idx2),A2_VRS2->vectorReg.read(io.ibuffer_if_ctrl.reg_idx2)))
  val read3=MuxLookup(io.ibuffer_if_ctrl.sel_alu3,false.B,Array(A3_VRS3->vectorReg.read(io.ibuffer_if_ctrl.reg_idx3),A3_SD->Mux(io.ibuffer_if_ctrl.isvec,vectorReg.read(io.ibuffer_if_ctrl.reg_idx3),scalarReg.read(io.ibuffer_if_ctrl.reg_idx2))))
  val readm=Mux(io.ibuffer_if_ctrl.mask,vectorReg.read(0.U),false.B)
  val readw=Mux(io.ibuffer_if_ctrl.wxd,scalarReg.read(io.ibuffer_if_ctrl.reg_idxw),false.B)|Mux(io.ibuffer_if_ctrl.wfd,vectorReg.read(io.ibuffer_if_ctrl.reg_idxw),false.B)
  val readb=beqReg.read(0.U)
  val readf=io.ibuffer_if_ctrl.mem & fenceReg.read(0.U)
  io.delay:=read1|read2|read3|readm|readb|readf
}