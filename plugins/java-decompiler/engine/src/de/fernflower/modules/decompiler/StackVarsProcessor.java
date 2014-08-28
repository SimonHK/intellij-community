/*
 *    Fernflower - The Analytical Java Decompiler
 *    http://www.reversed-java.com
 *
 *    (C) 2008 - 2010, Stiver
 *
 *    This software is NEITHER public domain NOR free software 
 *    as per GNU License. See license.txt for more details.
 *
 *    This software is distributed WITHOUT ANY WARRANTY; without 
 *    even the implied warranty of MERCHANTABILITY or FITNESS FOR 
 *    A PARTICULAR PURPOSE. 
 */

package org.jetbrains.java.decompiler.modules.decompiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.jetbrains.java.decompiler.code.CodeConstants;
import org.jetbrains.java.decompiler.modules.decompiler.exps.AssignmentExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.Exprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.MonitorExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.NewExprent;
import org.jetbrains.java.decompiler.modules.decompiler.exps.VarExprent;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectGraph;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.DirectNode;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.FlattenStatementsHelper;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.SSAConstructorSparseEx;
import org.jetbrains.java.decompiler.modules.decompiler.sforms.SSAUConstructorSparseEx;
import org.jetbrains.java.decompiler.modules.decompiler.stats.DoStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement;
import org.jetbrains.java.decompiler.modules.decompiler.stats.Statement;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionEdge;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionNode;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionPaar;
import org.jetbrains.java.decompiler.modules.decompiler.vars.VarVersionsGraph;
import org.jetbrains.java.decompiler.struct.StructClass;
import org.jetbrains.java.decompiler.struct.StructMethod;
import org.jetbrains.java.decompiler.util.FastSparseSetFactory.FastSparseSet;
import org.jetbrains.java.decompiler.util.InterpreterUtil;
import org.jetbrains.java.decompiler.util.SFormsFastMapDirect;


public class StackVarsProcessor {

	public void simplifyStackVars(RootStatement root, StructMethod mt, StructClass cl) {
		
		HashSet<Integer> setReorderedIfs = new HashSet<Integer>(); 
		
		SSAUConstructorSparseEx ssau = null;
		
		for(;;) {
			
			boolean found = false;

//			System.out.println("--------------- \r\n"+root.toJava());
			
			SSAConstructorSparseEx ssa = new SSAConstructorSparseEx();
			ssa.splitVariables(root, mt);
			
//			System.out.println("--------------- \r\n"+root.toJava());
			
			
			SimplifyExprentsHelper sehelper = new SimplifyExprentsHelper(ssau == null);
			while(sehelper.simplifyStackVarsStatement(root, setReorderedIfs, ssa, cl)) {
//				System.out.println("--------------- \r\n"+root.toJava());
				found = true;
			}

			
//			System.out.println("=============== \r\n"+root.toJava());
			
			setVersionsToNull(root);
			
			SequenceHelper.condenseSequences(root);
			
			ssau = new SSAUConstructorSparseEx();
			ssau.splitVariables(root, mt);
			
//			try {
//				DotExporter.toDotFile(ssau.getSsuversions(), new File("c:\\Temp\\gr12_my.dot"));
//			} catch(Exception ex) {
//				ex.printStackTrace();
//			}
			
//			System.out.println("++++++++++++++++ \r\n"+root.toJava());

			
			if(iterateStatements(root, ssau)) {
				found = true;
			}
			
//			System.out.println("***************** \r\n"+root.toJava());
			
			setVersionsToNull(root);
			
			if(!found) {
				break;
			}
		}
		
		// remove unused assignments
		ssau = new SSAUConstructorSparseEx();
		ssau.splitVariables(root, mt);
		
//		try {
//			DotExporter.toDotFile(ssau.getSsuversions(), new File("c:\\Temp\\gr12_my.dot"));
//		} catch(Exception ex) {
//			ex.printStackTrace();
//		}
		
		iterateStatements(root, ssau);

//		System.out.println("~~~~~~~~~~~~~~~~~~~~~~ \r\n"+root.toJava());
		
		setVersionsToNull(root);
	}
	
	private void setVersionsToNull(Statement stat) {
		
		if(stat.getExprents() == null) {		
			for(Object obj: stat.getSequentialObjects()) {
				if(obj instanceof Statement) {
					setVersionsToNull((Statement)obj);
				} else if(obj instanceof Exprent) {
					setExprentVersionsToNull((Exprent)obj);
				}
			}
		} else {
			for(Exprent exprent: stat.getExprents()) {
				setExprentVersionsToNull(exprent);
			}
		}
	}
	
	private void setExprentVersionsToNull(Exprent exprent) {
		
		List<Exprent> lst = exprent.getAllExprents(true);
		lst.add(exprent);
		
		for(Exprent expr: lst) {
			if(expr.type == Exprent.EXPRENT_VAR) {
				((VarExprent)expr).setVersion(0);
			}
		}
	}
	
	
	private boolean iterateStatements(RootStatement root, SSAUConstructorSparseEx ssa) {

		FlattenStatementsHelper flatthelper = new FlattenStatementsHelper();
		DirectGraph dgraph = flatthelper.buildDirectGraph(root);
		
		boolean res = false; 
		
		HashSet<DirectNode> setVisited = new HashSet<DirectNode>(); 
		LinkedList<DirectNode> stack = new LinkedList<DirectNode>();
		LinkedList<HashMap<VarVersionPaar, Exprent>> stackMaps = new LinkedList<HashMap<VarVersionPaar, Exprent>>();
		
		stack.add(dgraph.first);
		stackMaps.add(new HashMap<VarVersionPaar, Exprent>());
		
		while(!stack.isEmpty()) {
			
			DirectNode nd = stack.removeFirst();
			HashMap<VarVersionPaar, Exprent> mapVarValues = stackMaps.removeFirst();
			
			if(setVisited.contains(nd)) {
				continue;
			}
			setVisited.add(nd);
			
			List<List<Exprent>> lstLists = new ArrayList<List<Exprent>>();

			if(!nd.exprents.isEmpty()) {
				lstLists.add(nd.exprents);
			}
			
			if(nd.succs.size() == 1){
				DirectNode ndsucc = nd.succs.get(0);
				if(ndsucc.type == DirectNode.NODE_TAIL && !ndsucc.exprents.isEmpty()) {
					lstLists.add(nd.succs.get(0).exprents);
					nd = ndsucc;
				}
			}
			
			for(int i=0;i<lstLists.size();i++) {
				List<Exprent> lst = lstLists.get(i);
				
				int index = 0;
				while(index < lst.size()) {
					Exprent next = null;
					if(index == lst.size()-1) {
						if(i<lstLists.size()-1) {
							next = lstLists.get(i+1).get(0);
						}
					} else {
						next = lst.get(index+1);
					}
					
					int[] ret = iterateExprent(lst, index, next, mapVarValues, ssa);
					
					//System.out.println("***************** \r\n"+root.toJava());
					
					if(ret[0] >= 0) {
						index = ret[0];
					} else {
						index++;
					}
					res |= (ret[1] == 1);
				}
			}
			
			for(DirectNode ndx: nd.succs) {
				stack.add(ndx);
				stackMaps.add(new HashMap<VarVersionPaar, Exprent>(mapVarValues));
			}
			
			// make sure the 3 special exprent lists in a loop (init, condition, increment) are not empty
			// change loop type if necessary
			if(nd.exprents.isEmpty() && 
					(nd.type == DirectNode.NODE_INIT || nd.type == DirectNode.NODE_CONDITION || nd.type == DirectNode.NODE_INCREMENT)) {
				nd.exprents.add(null);
				
				if(nd.statement.type == Statement.TYPE_DO) {
					DoStatement loop = (DoStatement)nd.statement;
					
					if(loop.getLooptype() == DoStatement.LOOP_FOR && loop.getInitExprent() == null && loop.getIncExprent() == null) { // "downgrade" loop to 'while'
						loop.setLooptype(DoStatement.LOOP_WHILE);
					}
				}
			}
		}
		
		return res;
	}
	
		
	private Exprent isReplaceableVar(Exprent exprent, HashMap<VarVersionPaar, Exprent> mapVarValues, SSAUConstructorSparseEx ssau) {

		Exprent dest = null;
		
		if(exprent.type == Exprent.EXPRENT_VAR) {
			VarExprent var = (VarExprent)exprent;
			dest = mapVarValues.get(new VarVersionPaar(var));
		}
		
		return dest;
	}

	private void replaceSingleVar(Exprent parent, VarExprent var, Exprent dest, SSAUConstructorSparseEx ssau) {

		parent.replaceExprent(var, dest);
		
		// live sets
		SFormsFastMapDirect livemap = ssau.getLiveVarVersionsMap(new VarVersionPaar(var));
		HashSet<VarVersionPaar> setVars = getAllVersions(dest);
		
		for(VarVersionPaar varpaar : setVars) {
			VarVersionNode node = ssau.getSsuversions().nodes.getWithKey(varpaar);
			
			for(Iterator<Entry<Integer, FastSparseSet<Integer>>> itent = node.live.entryList().iterator();itent.hasNext();) {
				Entry<Integer, FastSparseSet<Integer>> ent = itent.next();
				
				Integer key = ent.getKey();
				
				if(!livemap.containsKey(key)) {
					itent.remove();
				} else {
					FastSparseSet<Integer> set = ent.getValue();

					set.complement(livemap.get(key));
					if(set.isEmpty()) {
						itent.remove();
					}
				}
			}
		}
	}
	
	private int[] iterateExprent(List<Exprent> lstExprents, int index, Exprent next, HashMap<VarVersionPaar, 
																	Exprent> mapVarValues, SSAUConstructorSparseEx ssau) {
		
		Exprent exprent = lstExprents.get(index);
		
		int changed = 0;
		
		for(Exprent expr: exprent.getAllExprents()) {
			for(;;) {
				Object[] arr = iterateChildExprent(expr, exprent, next, mapVarValues, ssau);
				Exprent retexpr = (Exprent)arr[0];
				changed |= (Boolean)arr[1]?1:0;
				
				boolean isReplaceable = (Boolean)arr[2];
				if(retexpr != null) {
					if(isReplaceable) {
						replaceSingleVar(exprent, (VarExprent)expr, retexpr, ssau);
						expr = retexpr;
					} else {
						exprent.replaceExprent(expr, retexpr);
					}
					changed = 1;
				}
				
				if(!isReplaceable) {
					break;
				}
			}
		}
	
		// no var on the highest level, so no replacing
		
		VarExprent left = null;
		Exprent right = null;
		
		if(exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
			AssignmentExprent as = (AssignmentExprent)exprent;
			if(as.getLeft().type == Exprent.EXPRENT_VAR) {
				left = (VarExprent)as.getLeft();
				right = as.getRight();
			}
		}
		
		if(left == null) {
			return new int[]{-1, changed};
		}
		
		VarVersionPaar leftpaar = new VarVersionPaar(left);
		
		List<VarVersionNode> usedVers = new ArrayList<VarVersionNode>(); 
		boolean notdom = getUsedVersions(ssau, leftpaar, usedVers);
		
		if(!notdom && usedVers.isEmpty()) { 
			if(left.isStack() && (right.type == Exprent.EXPRENT_INVOCATION || 
					right.type == Exprent.EXPRENT_ASSIGNMENT ||	right.type == Exprent.EXPRENT_NEW)) { 
				if(right.type == Exprent.EXPRENT_NEW) {
					// new Object(); permitted
					NewExprent nexpr = (NewExprent)right;
					if(nexpr.isAnonymous() || nexpr.getNewtype().arraydim > 0
							|| nexpr.getNewtype().type != CodeConstants.TYPE_OBJECT) {
						return new int[]{-1, changed};
					}
				}
				
				lstExprents.set(index, right);
				return new int[]{index+1, 1};
			} else if(right.type == Exprent.EXPRENT_VAR) {
				lstExprents.remove(index);
				return new int[]{index, 1};
			} else {
				return new int[]{-1, changed};
			}
		}
		
		int useflags = right.getExprentUse();

		// stack variables only
		if(!left.isStack() && 
				(right.type != Exprent.EXPRENT_VAR || ((VarExprent)right).isStack())) { // special case catch(... ex)
			return new int[]{-1, changed};
		}

		if((useflags & Exprent.MULTIPLE_USES) == 0  && (notdom || usedVers.size()>1)) {
			return new int[]{-1, changed};
		} 
		
		HashMap<Integer, HashSet<VarVersionPaar>> mapVars = getAllVarVersions(leftpaar, right, ssau);

		boolean isSelfReference = mapVars.containsKey(leftpaar.var);
		if(isSelfReference && notdom) {
			return new int[]{-1, changed};
		} 
		
		HashSet<VarVersionPaar> setNextVars = next==null?null:getAllVersions(next);
		
		// FIXME: fix the entire method!
		if(right.type != Exprent.EXPRENT_CONST && right.type != Exprent.EXPRENT_VAR && setNextVars!=null && mapVars.containsKey(leftpaar.var)) {
			for(VarVersionNode usedvar: usedVers) {
				if(!setNextVars.contains(new VarVersionPaar(usedvar.var, usedvar.version))) {
					return new int[]{-1, changed};
				}
			}
		}
		
		mapVars.remove(leftpaar.var);
		
		boolean vernotreplaced = false;
		boolean verreplaced = false;
		
		
		HashSet<VarVersionPaar> setTempUsedVers = new HashSet<VarVersionPaar>();
		
		for(VarVersionNode usedvar: usedVers) {
			VarVersionPaar usedver = new VarVersionPaar(usedvar.var, usedvar.version);
			if(isVersionToBeReplaced(usedver, mapVars, ssau, leftpaar) && 
					(right.type == Exprent.EXPRENT_CONST || right.type == Exprent.EXPRENT_VAR || right.type == Exprent.EXPRENT_FIELD  
							|| setNextVars==null || setNextVars.contains(usedver))) {
				
				setTempUsedVers.add(usedver);
				verreplaced = true;
			} else {
				vernotreplaced = true;
			}
		}
		
		if(isSelfReference && vernotreplaced) {
			return new int[]{-1, changed};
		} else {
			for(VarVersionPaar usedver: setTempUsedVers) {
				Exprent copy = right.copy();
				if(right.type == Exprent.EXPRENT_FIELD && ssau.getMapFieldVars().containsKey(right.id)) {
					ssau.getMapFieldVars().put(copy.id, ssau.getMapFieldVars().get(right.id));
				}
				
				mapVarValues.put(usedver, copy);
			}
		}
		
		if(!notdom && !vernotreplaced) {
			// remove assignment
			lstExprents.remove(index);
			return new int[]{index, 1};
		} else if(verreplaced){
			return new int[]{index+1, changed};
		} else {
			return new int[]{-1, changed};
		}
	}
	
	private HashSet<VarVersionPaar> getAllVersions(Exprent exprent) {
		
		HashSet<VarVersionPaar> res = new HashSet<VarVersionPaar>();
		
		List<Exprent> listTemp = new ArrayList<Exprent>(exprent.getAllExprents(true));
		listTemp.add(exprent);

		for(Exprent expr: listTemp) {
			if(expr.type == Exprent.EXPRENT_VAR) {
				VarExprent var = (VarExprent)expr;
				res.add(new VarVersionPaar(var));
			}
		}
		
		return res;
	}
	
	private Object[] iterateChildExprent(Exprent exprent, Exprent parent, Exprent next, HashMap<VarVersionPaar, Exprent> mapVarValues, SSAUConstructorSparseEx ssau) {

		boolean changed = false;
		
		for(Exprent expr: exprent.getAllExprents()) {
			for(;;) {
				Object[] arr = iterateChildExprent(expr, parent, next, mapVarValues, ssau);
				Exprent retexpr = (Exprent)arr[0];
				changed |= (Boolean)arr[1];
				
				boolean isReplaceable = (Boolean)arr[2];
				if(retexpr != null) {
					if(isReplaceable) {
						replaceSingleVar(exprent, (VarExprent)expr, retexpr, ssau);
						expr = retexpr;
					} else {
						exprent.replaceExprent(expr, retexpr);
					}
					changed = true;
				}
				
				if(!isReplaceable) {
					break;
				}
			}
		}
		
		Exprent dest = isReplaceableVar(exprent, mapVarValues, ssau);
		if(dest != null) {
			return new Object[]{dest, true, true};
		}

		
		VarExprent left = null;
		Exprent right = null;
		
		if(exprent.type == Exprent.EXPRENT_ASSIGNMENT) {
			AssignmentExprent as = (AssignmentExprent)exprent;
			if(as.getLeft().type == Exprent.EXPRENT_VAR) {
				left = (VarExprent)as.getLeft();
				right = as.getRight();
			}
		}
		
		if(left == null) {
			return new Object[]{null, changed, false};
		}
		
		boolean isHeadSynchronized = false;
		if(next == null && parent.type == Exprent.EXPRENT_MONITOR) {
			MonitorExprent monexpr = (MonitorExprent)parent;
			if(monexpr.getMontype() == MonitorExprent.MONITOR_ENTER && exprent.equals(monexpr.getValue())) {
				isHeadSynchronized = true;
			}
		}
		
		// stack variable or synchronized head exprent
		if(!left.isStack() && !isHeadSynchronized) {
			return new Object[]{null, changed, false};
		}
		
		VarVersionPaar leftpaar = new VarVersionPaar(left);
		
		List<VarVersionNode> usedVers = new ArrayList<VarVersionNode>(); 
		boolean notdom = getUsedVersions(ssau, leftpaar, usedVers);
		
		if(!notdom && usedVers.isEmpty()) { 
			return new Object[]{right, changed, false};
		}

		// stack variables only
		if(!left.isStack()) {
			return new Object[]{null, changed, false};
		}
		
		int useflags = right.getExprentUse();

		if((useflags & Exprent.BOTH_FLAGS) != Exprent.BOTH_FLAGS) {
			return new Object[]{null, changed, false};
		} 
		
		HashMap<Integer, HashSet<VarVersionPaar>> mapVars = getAllVarVersions(leftpaar, right, ssau);
		
		if(mapVars.containsKey(leftpaar.var) && notdom) {
			return new Object[]{null, changed, false};
		} 
		
		
		mapVars.remove(leftpaar.var);
		
		HashSet<VarVersionPaar> setAllowedVars = getAllVersions(parent);
		if(next != null) {
			setAllowedVars.addAll(getAllVersions(next));
		}
		
		boolean vernotreplaced = false;
		
		HashSet<VarVersionPaar> setTempUsedVers = new HashSet<VarVersionPaar>();
		
		for(VarVersionNode usedvar: usedVers) {
			VarVersionPaar usedver = new VarVersionPaar(usedvar.var, usedvar.version);
			if(isVersionToBeReplaced(usedver, mapVars, ssau, leftpaar) && 
					(right.type == Exprent.EXPRENT_VAR || setAllowedVars.contains(usedver))) {
				
				setTempUsedVers.add(usedver);
			} else {
				vernotreplaced = true;
			}
		}
		
		if(!notdom && !vernotreplaced) {
			
			for(VarVersionPaar usedver: setTempUsedVers) {
				Exprent copy = right.copy();
				if(right.type == Exprent.EXPRENT_FIELD && ssau.getMapFieldVars().containsKey(right.id)) {
					ssau.getMapFieldVars().put(copy.id, ssau.getMapFieldVars().get(right.id));
				}

				mapVarValues.put(usedver, copy);
			}
			
			// remove assignment
			return new Object[]{right, changed, false};
		} 

		return new Object[]{null, changed, false};
	}
	
	private boolean getUsedVersions(SSAUConstructorSparseEx ssa, VarVersionPaar var, List<VarVersionNode> res) {
		
		VarVersionsGraph ssuversions = ssa.getSsuversions();
		VarVersionNode varnode = ssuversions.nodes.getWithKey(var);
		
		HashSet<VarVersionNode> setVisited = new HashSet<VarVersionNode>();

		HashSet<VarVersionNode> setNotDoms = new HashSet<VarVersionNode>();
		
		LinkedList<VarVersionNode> stack = new LinkedList<VarVersionNode>();
		stack.add(varnode);
		
		while(!stack.isEmpty()) {
			
			VarVersionNode nd = stack.remove(0);
			setVisited.add(nd);
			
			if(nd != varnode && (nd.flags & VarVersionNode.FLAG_PHANTOM_FINEXIT)==0) {
				res.add(nd);
			}

			for(VarVersionEdge edge: nd.succs) {
				VarVersionNode succ = edge.dest;
				
				if(!setVisited.contains(edge.dest)) {
					
					boolean isDominated = true;
					for(VarVersionEdge prededge : succ.preds) {
						if(!setVisited.contains(prededge.source)) {
							isDominated = false;
							break;
						}
					}
					
					if(isDominated) {
						stack.add(succ);
					} else {
						setNotDoms.add(succ);
					}
				}
			}
		}
		
		setNotDoms.removeAll(setVisited);
		
		return !setNotDoms.isEmpty();
	}
	
	private boolean isVersionToBeReplaced(VarVersionPaar usedvar, HashMap<Integer, HashSet<VarVersionPaar>> mapVars, SSAUConstructorSparseEx ssau, VarVersionPaar leftpaar) {
		
		VarVersionsGraph ssuversions = ssau.getSsuversions();
		
		SFormsFastMapDirect mapLiveVars = ssau.getLiveVarVersionsMap(usedvar);
		if(mapLiveVars == null) {
			// dummy version, predecessor of a phi node
			return false;
		}
		
		// compare protected ranges
		if(!InterpreterUtil.equalObjects(ssau.getMapVersionFirstRange().get(leftpaar), 
				ssau.getMapVersionFirstRange().get(usedvar))) {
			return false;
		}
		
		for(Entry<Integer, HashSet<VarVersionPaar>> ent: mapVars.entrySet()) {
			FastSparseSet<Integer> liveverset = mapLiveVars.get(ent.getKey());
			if(liveverset == null) {
				return false;
			}
			
			HashSet<VarVersionNode> domset = new HashSet<VarVersionNode>();
			for(VarVersionPaar verpaar: ent.getValue()) {
				domset.add(ssuversions.nodes.getWithKey(verpaar));
			}
			
			boolean isdom = false;
			
			for(Integer livever: liveverset) {
				VarVersionNode node = ssuversions.nodes.getWithKey(new VarVersionPaar(ent.getKey().intValue(), livever.intValue()));
				
				if(ssuversions.isDominatorSet(node, domset)) {
					isdom = true;
					break;
				}
			}
			
			if(!isdom) {
				return false;
			}
		}
		
		return true;
	}

	private HashMap<Integer, HashSet<VarVersionPaar>> getAllVarVersions(VarVersionPaar leftvar, Exprent exprent, SSAUConstructorSparseEx ssau) {
		
		HashMap<Integer, HashSet<VarVersionPaar>> map = new HashMap<Integer, HashSet<VarVersionPaar>>();
		SFormsFastMapDirect mapLiveVars = ssau.getLiveVarVersionsMap(leftvar);
		
		List<Exprent> lst = exprent.getAllExprents(true);
		lst.add(exprent);

		for(Exprent expr: lst) {
			if(expr.type == Exprent.EXPRENT_VAR) {
				int varindex = ((VarExprent)expr).getIndex();
				if(leftvar.var != varindex) {
					if(mapLiveVars.containsKey(varindex)) {
						HashSet<VarVersionPaar> verset = new HashSet<VarVersionPaar>();
						for(Integer vers: mapLiveVars.get(varindex)) {
							verset.add(new VarVersionPaar(varindex, vers.intValue()));
						}
						map.put(varindex, verset);
					} else {
						throw new RuntimeException("inkonsistent live map!");
					}
				} else {
					map.put(varindex, null);
				}
			} else if(expr.type == Exprent.EXPRENT_FIELD) {
				if(ssau.getMapFieldVars().containsKey(expr.id)) {
					int varindex = ssau.getMapFieldVars().get(expr.id);
					if(mapLiveVars.containsKey(varindex)) {
						HashSet<VarVersionPaar> verset = new HashSet<VarVersionPaar>();
						for(Integer vers: mapLiveVars.get(varindex)) {
							verset.add(new VarVersionPaar(varindex, vers.intValue()));
						}
						map.put(varindex, verset);
					}
				}
			}
		}
		
		return map;
	}
	
}
