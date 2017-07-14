package food.parse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

import com.google.common.collect.Multimap;
import com.wolfram.jlink.Expr;
import com.wolfram.jlink.ExprFormatException;

import food.parse.FoodStruct.FoodStructType;
import food.utils.FoodLexicon;
import thmp.parse.ParseStruct;
import thmp.parse.ParseStructType;
import thmp.parse.Struct;
import thmp.parse.Struct.ChildRelation;
import thmp.parse.StructH;
import thmp.parse.ParseToWLTree.WLCommandWrapper;
import thmp.parse.WLCommand.PosTerm;
import thmp.parse.WLCommand;
import thmp.parse.WLCommandsList;
import thmp.utils.WordForms;

/**
 * Graph whose vertices are FoodState's, and edges
 * are RecipeEdge's.
 * Holds meta info on the recipe.
 * 
 * @author yihed
 */
public class RecipeGraph {
	
	//set of verbs that can represent action to prior FoodState, e.g. "add".
	private static final Set<String> VERB_PRIOR_SET;
	//prepositions denoting source of actions
	private static final Set<String> ACTION_SOURCE_SET;
	//prepositions denoting target of actions
	private static final Set<String> ACTION_TARGET_SET;		
	private static final Pattern TIME_PATTERN = Pattern.compile(".*(?:second|minute|hour|day|overnight)s*.*");
	//ingredients, and whether used or not
	private Map<String, Boolean> ingredientsMap;
	//beginning nodes in 
	//private List<FoodState> ingredientStateList;
	//List of current FoodState's. 
	private List<FoodState> currentStateList;
	//immediate prior FoodState, to be used in next parse, 
	//if next parse makes implicit reference to lastFoodState.
	private FoodState lastFoodState = FoodState.foodStateSingletonInstance();
	private Random randomNumGenerator = new Random();	
	
	static{
		String[] verbPriorAr = new String[]{"add"};
		VERB_PRIOR_SET = new HashSet<String>(Arrays.asList(verbPriorAr));
		String[] actionSourceAr = new String[]{"from"};
		String[] actionTargetAr = new String[]{"to", "into", "onto"};
		ACTION_SOURCE_SET = new HashSet<String>(Arrays.asList(actionSourceAr));
		ACTION_TARGET_SET = new HashSet<String>(Arrays.asList(actionTargetAr));
	}
	
	private RecipeGraph(//List<FoodState> ingredientStateList_, 
			Map<String, Boolean> ingredientsMap_){
		//this.currentStateList = ingredientStateList_;
		this.currentStateList = new ArrayList<FoodState>();
		this.ingredientsMap = ingredientsMap_;
		
		//this.ingredientsSet = new HashSet<String>();
		///ingredientsSet.addAll(ingredientStateList_);
	}
	
	/**
	 * Constructs recipe graph, filling in ingredients states with initial list
	 * of ingredients.
	 * @param ingredientsList
	 * @return
	 */
	public static RecipeGraph initializeRecipeGraph(List<String> ingredientsList){
		//
		//List<FoodState> ingredientsStateList = new ArrayList<FoodState>();
		Map<String, Boolean> ingredientsMap = new HashMap<String, Boolean>();
		
		for(String ingredient : ingredientsList){
			//FoodState foodState = new FoodState(ingredient);
			//ingredientsStateList.add(foodState);
			ingredientsMap.put(ingredient, false);			
		}
		return new RecipeGraph(ingredientsMap);
	}

	/**
	 * Append and update currentStateList with the input instruction
	 * Expr.
	 * 
	 * @param recipeExpr E.g. Action[Math["filter"], Math["Type"["flag"], 
	 * Qualifiers["over", Math["Type"["field"]]]]]
	 */
	public void updateFoodStates(ParseStruct headParseStruct){
		
		//winning wrapper map on the top level
		Multimap<ParseStructType, WLCommandWrapper> wrapperMMap = headParseStruct.getWLCommandWrapperMMap();
		//System.out.println("wrapperMMap.values().size "+ wrapperMMap.values().size());
		for(WLCommandWrapper wrapper : wrapperMMap.values()){
			WLCommand wlCommand = wrapper.WLCommand();
			handlePosTermList(wlCommand);
		}		
		//first is head, 2nd and 3rd should be food items
		/*Expr[] exprArgs = recipeExpr.args();
		if(exprArgs.length < 2){
			return;
		}
		//first search under ingredients map, then current food state list.
		//eg Action Head is Expr(Expr.SYMBOL, "Action")
		Expr headExpr = recipeExpr.head();
		Expr[] headExprArgs = headExpr.args();
		String actionStr = "";
		if(headExprArgs.length > 1){
			actionStr = headExprArgs[1].toString();
		}		
		*/	
	}
	
	/**
	 * Should handle different heads, e.g. Action, differently.
	 * @param posList
	 */
	private void handlePosTermList(WLCommand wlCommand){
		//here assume it's Action. Refine
		List<PosTerm> posList = WLCommand.posTermList(wlCommand);
		int triggerTermIndex = WLCommand.triggerWordIndex(wlCommand);
		//List<Struct> knownStructList = new ArrayList<Struct>();
		List<FoodState> knownStateList = new ArrayList<FoodState>();
		//could be utensils, or the name of newly created item
		//Could be multiple, e.g. separate egg into whites and yolk.
		//Or e.g. "in the bowl", or "into a ball"
		List<Struct> unknownStructList = new ArrayList<Struct>();
		List<FoodStruct> actionSourceList = new ArrayList<FoodStruct>(); 
		List<FoodStruct> actionTargetList = new ArrayList<FoodStruct>();
		/*if(posList.get(triggerTermIndex).posTermStruct().nameStr().equals("combine")){
			System.out.println("RecipeGraph combine");
		}*/
		//triggerSubject, subject of the trigger term, e.g. "potatoes" in "smash potatoes"
		boolean prevTermIsTrigger = false;
		int posListSz = posList.size();
		for(int i = 0; i < posListSz; i++){
			PosTerm term = posList.get(i);			
			int posInMap = term.positionInMap();
			if(!term.includeInBuiltString() || triggerTermIndex == i
					|| posInMap == WLCommandsList.AUXINDEX 
					|| posInMap == WLCommandsList.WL_DIRECTIVE_INDEX){
				if(triggerTermIndex == i){
					prevTermIsTrigger = true;
				}
				continue;
			}
			Struct termStruct = term.posTermStruct();
			if(null == termStruct){
				continue;
			}			
			addStructFoodState(unknownStructList, knownStateList, actionSourceList, actionTargetList,
					prevTermIsTrigger, termStruct);	
			prevTermIsTrigger = false;
		}		
		//to go along with edge, could be e.g. "until warm", "in oven"
		List<Struct> edgeQualifierStructList = new ArrayList<Struct>();				
		Struct actionStruct;
		//whether lastFoodState has been added to knownStateList.
		boolean lastStateUsed = false;
		//need to check if -1, or some default value!		
		if(triggerTermIndex < 0){
			// improve!
			Map<String, String> map = new HashMap<String, String>();
			map.put("name", "");
			actionStruct = new StructH<Map<String, String>>(map, "unknown");
		}else{
			PosTerm triggerTerm = posList.get(triggerTermIndex);
			actionStruct = triggerTerm.posTermStruct();
			String actionStructName = actionStruct.nameStr();
			//if actionStruct has child, e.g. "stir in", "combine with",
			//means subject refers to immediately prior product.
			//Or verb alone represents action to prior FoodState, e.g. "add".
			//but only if not explicit "add .. to .." <--check more explicit than 
			//notKnownStructList empty?
			if(actionStruct.has_child() || VERB_PRIOR_SET.contains(actionStructName) && actionTargetList.isEmpty()){
				//purge previous known states? Since they probably don't refer to prior food states?
				knownStateList.add(lastFoodState);
				removeLastFoodStateFromCurrentList();
				lastStateUsed = true;
				for(Struct childStruct : actionStruct.children()){
					edgeQualifierStructList.add(childStruct);
				}
			}
		}		
		List<Struct> productStructList = new ArrayList<Struct>();
		List<Struct> notKnownStructList = new ArrayList<Struct>();
		//construct edge from trigger term and unknown structs
		//System.out.println("unknownStructList "+unknownStructList);
		for(Struct struct : unknownStructList){
			//decide whether utensil, or appliance!
			String structName = struct.nameStr();
			if(isAppliance(structName)){		
				edgeQualifierStructList.add(struct);
			}else if(isFood(structName)){
				addStructToList(knownStateList, productStructList, edgeQualifierStructList, struct, lastStateUsed);
			}else{
				addStructToList(knownStateList, notKnownStructList, edgeQualifierStructList, struct, lastStateUsed);
			}
		}
		//System.out.println("RecipeGraph - knownStateList "+knownStateList);
		//add not known for now, refine
		//need at least one product, or else should use substitute list, rather than 
		//the actual currentStateList
		//in oven should be appliacen, but form rice mixture should go to product, be smarter
		
		/*notKnownStructList elements should be added to edgeQualifierStructList, if food struct*/
		/*for(Struct notKnownStruct : notKnownStructList){
			if(isEdgeQualifier(notKnownStruct)){
				//e.g. bake for 20 minutes
				edgeQualifierStructList.add(notKnownStruct);
			}else{
				//e.g. "form rice mixture into balls"
				productStructList.add(notKnownStruct);
			}
		}*/
		if(productStructList.isEmpty()){
			productStructList.addAll(notKnownStructList);
		}else{
			edgeQualifierStructList.addAll(notKnownStructList);			
		}
		
		if(knownStateList.isEmpty()){
			knownStateList.add(lastFoodState);
			removeLastFoodStateFromCurrentList();
		}
		RecipeEdge recipeEdge = new RecipeEdge(actionStruct, edgeQualifierStructList);
		for(FoodState parentState : knownStateList){
			parentState.setChildEdge(recipeEdge);
		}
		//target of edge.
		if(productStructList.isEmpty()){
			//improve! Create some placeholder to represent state
			Map<String, String> map = new HashMap<String, String>();					
			map.put("name", String.valueOf(randomNumGenerator.nextInt(5000))); //graph thinks same vertex, if same as previous!! need unique identifier!
			//map.put("name", ""); //graph thinks same vertex, if same as previous!! need unique identifier!
			productStructList.add(new StructH<Map<String, String>>(map, ""));
		}
		//need to be careful! Should not allow multiple parents and multiple children at same time!		
		for(Struct product : productStructList){
			FoodState productState = new FoodState(product, knownStateList, recipeEdge);
			//add parents
			for(FoodState parentState : knownStateList){
				parentState.addChildFoodState(productState);
			}
			currentStateList.add(productState);
			//try to pick out the most likely state?
			lastFoodState = productState;
		}		
	}

	/**
	 * 
	 */
	private void removeLastFoodStateFromCurrentList() {
		int currentStateListSz = currentStateList.size();
		if(currentStateListSz > 0){
			//this assumes the lastFoodState is the last added to list
			//need to update if that's no longer true.
			currentStateList.remove(currentStateListSz-1);
		}
	}

	/**
	 * Whether the struct qualifies edge.
	 * e.g. "bake in oven", "wait 20 minutes"
	 * @param notKnownStruct
	 * @return
	 */
	private boolean isEdgeQualifier(Struct struct) {
		if(struct.isFoodStruct() && !"".equals(((FoodStruct)struct).qualifier())){
			//maybe check the struct's qualifier string, such as "in", "for" etc
			// && !"".equals(((FoodStruct)notKnownStruct).qualifier())
			return true;
		}
		String structName = struct.nameStr();
		if(TIME_PATTERN.matcher(structName).matches()){			
			return true;
		}
		return false;
	}

	/**
	 * Determines which list, whether known state, product list, etc, to add to for each struct.
	 * @param knownStateList
	 * @param addToStructList
	 * @param struct
	 * @param lastStateUsed Whether lastFoodState has been added in building this edge step.
	 */
	private void addStructToList(List<FoodState> knownStateList, List<Struct> addToStructList, 
			List<Struct> edgeQualifierStructList, Struct struct, boolean lastStateUsed) {
		boolean structAdded = false;
		//if(true) throw new RuntimeException();
		if(struct.isFoodStruct()){
			FoodStruct foodStruct = (FoodStruct)struct;			
			if(isEdgeQualifier(struct)){
				////e.g. bake for 20 minutes
				edgeQualifierStructList.add(struct);
				structAdded = true;
			}else if(!lastStateUsed && (foodStruct.foodStructType() == FoodStructType.SUBJECT
					//e.g. "wash veggies", "bake batter'
					|| knownStateList.isEmpty())){	
				//can't set struct, as will affect edge formation.<--not if haven't formed Expr's.
				//need to avoid name clashes, in case struct has same name as some previous one.
				lastFoodState.setFoodStruct(foodStruct);
				removeLastFoodStateFromCurrentList();
				knownStateList.add(lastFoodState);			
				structAdded = true;
			}
		}
		if(!structAdded){
			addToStructList.add(struct);					
		}
	}

	private boolean isFood(String structName){
		return FoodLexicon.foodMap().containsKey(structName);
	}
	
	private boolean isAppliance(String structName){		
		return FoodLexicon.equipmentMap().containsKey(structName);
	}
	/**
	 * @param unknownStructList
	 * @param termStruct
	 * @return
	 */
	private void addStructFoodState(List<Struct> unknownStructList, 
			List<FoodState> knownStateList, 
			List<FoodStruct> actionSourceList, List<FoodStruct> actionTargetList,
			boolean prevTermIsTrigger, Struct termStruct) {
		
		if(!termStruct.isStructA()){
			addStructFoodState2(unknownStructList, knownStateList, actionSourceList, actionTargetList, 
					prevTermIsTrigger, termStruct);
			//consider cases to look into prev1/prev2!? Need to handle e.g. prep!
		}else{
			String structType = termStruct.type();
			//conj
			if(structType.matches("(?:conj|disj)_.+")){
				//if(true)throw new RuntimeException(((Struct)(Struct)termStruct.prev2()).nameStr());
				if(termStruct.prev1NodeType().isTypeStruct()){
					addStructFoodState(unknownStructList, knownStateList, actionSourceList, actionTargetList,
							prevTermIsTrigger, (Struct)termStruct.prev1());
				}
				if(termStruct.prev2NodeType().isTypeStruct()){
					addStructFoodState(unknownStructList, knownStateList, actionSourceList, actionTargetList,
							prevTermIsTrigger, (Struct)termStruct.prev2());
				}
				//conj can have children nodes, e.g. "bake A and B in oven"
				addStructChildren(unknownStructList, knownStateList, actionSourceList, actionTargetList, 
						termStruct);
			}			
		}
		//return structType;
	}

	/**
	 * 
	 * @param unknownStructList
	 * @param knownStateList
	 * @param statesIter  Delete from iter, if previous FoodState corresponding to struct is found.
	 * @param termStruct
	 * @param structQualifier qualifier to struct, e.g. "over" in "over pan"
	 */
	private void addStructFoodState2(List<Struct> unknownStructList, List<FoodState> knownStateList,
			List<FoodStruct> actionSourceList, List<FoodStruct> actionTargetList, 
			//List<FoodToken> foodTokenList, 
			boolean prevTermIsTrigger, Struct termStruct, String...structQualifier) {
		//seek out the foodState this Struct is ascribed to
		String structName = termStruct.nameStr();
		//look amongst ingredients first
		Boolean ingredientUsed = ingredientsMap.get(structName);
		Struct structToAdd;
		//remember relation to parent, if applicable.
		if(prevTermIsTrigger){
			structToAdd = new FoodStruct(termStruct, FoodStructType.SUBJECT);
			//if(true) throw new RuntimeException("structToAdd "+structToAdd);
		}else if(structQualifier.length > 0){
			structToAdd = new FoodStruct(termStruct, structQualifier[0]);
		}else{
			structToAdd = termStruct;
		}
		//System.out.println("ingredientUsed "+structName);
		//if(structName.equals("baking soda")) throw new RuntimeException("ingredientUsed "+ingredientUsed);
		if(null != ingredientUsed && !ingredientUsed){			
			//**maybe should check if used or not, but shouldn't be used if match on nose
			FoodState foodState = new FoodState(structName, structToAdd);
			//add entry to currentStateList
			//currentStateList.add(foodState);
			boolean used = true;
			ingredientsMap.put(structName, used);
			knownStateList.add(foodState);
		}else{
			//look for other food states termStruct could be referring to
			//e.g. rice mixture could refer to something formed earlier.
			boolean knownStateFound = findPreviousFoodState(knownStateList, termStruct); 			
			if(!knownStateFound){
				//unless is action subject, e.g. "pour batter ..." in which case make 
				//it refer to the last state added to currentStateList.
				unknownStructList.add(structToAdd);				
				//if(true) throw new RuntimeException("structToAdd "+structToAdd.isFoodStruct());
			}
		}
		//add children
		addStructChildren(unknownStructList, knownStateList, actionSourceList, actionTargetList, 
				termStruct);
	}
	
	/**
	 * Look for other food states termStruct could be referring to
	 * e.g. rice mixture could refer to something formed earlier
	 * @return
	 */
	private boolean findPreviousFoodState(List<FoodState> knownStateList,// Iterator<FoodState> stateIter,
			Struct termStruct){
		String name = termStruct.nameStr();
		//String[] foodNameAr = WordForms.splitThmIntoSearchWords(name);
		//Set<String> nameSet = new HashSet<String>(Arrays.asList(foodNameAr));
		Iterator<FoodState> currentStateListIter = currentStateList.iterator();
		while(currentStateListIter.hasNext()){
			FoodState foodState = currentStateListIter.next();
			boolean ancestorMatched = stateAncestorMatchStruct(foodState, name);
			if(ancestorMatched){
				//update name if none already, e.g. "flour mixture"
				String newFoodName = foodState.foodName() + " " + name;
				foodState.setFoodName(newFoodName);
				/*if("".equals(foodState.foodName())){
					foodState.setFoodName(name);
				}*/
				knownStateList.add(foodState);
				currentStateListIter.remove();
				return true;
			}			
		}	
		return false;
	}
	
	/**
	 * Looks through ancestors, to see if any their names possibly matches
	 * struct's name.
	 * @param foodState Current state under consideration.
	 * @param sourceFoodName name of the food we are trying to find match for
	 * @return
	 */
	private boolean stateAncestorMatchStruct(FoodState foodState, String sourceFoodName //, Set<String> structNameSet
			){
		String foodStateName = foodState.foodName();
		//String[] foodNameAr = WordForms.splitThmIntoSearchWords(foodStateName) ;
		//System.out.println("foodNameAr - " +Arrays.toString(foodNameAr) + " structNameSet "+structNameSet);
		//Set<String> foodNameSet = new HashSet<String>(Arrays.asList(foodNameAr));
		/*for(String foodName : foodNameAr){
			//for now, only need one term to agree, e.g. rice and "rice mixture"
			if(structNameSet.contains(foodName)){
				return true;
			}
		}*/
		if(!WordForms.getWhiteEmptySpacePattern().matcher(foodStateName).matches() && sourceFoodName.contains(foodStateName)){
			//"rice mixture" contains "rice", but hot sauce doesn't match soy sauce
			return true;
		}
		List<FoodState> parentsList = foodState.parentFoodStateList();
		for(FoodState parentState : parentsList){
			if(stateAncestorMatchStruct(parentState, sourceFoodName)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Process struct's children, either with more ingredients,
	 * or appliances, e.g. "bake in oven"
	 * @param termStruct
	 * @param List<FoodState> sourceFoodStateList, source of action 
	 * @param actionTargetList, target of action. E.g. "to soup" in "add basil to soup"
	 */
	private void addStructChildren(List<Struct> unknownStructList, List<FoodState> knownStateList,
			List<FoodStruct> actionSourceList, List<FoodStruct> actionTargetList, //List<FoodToken> foodTokenList,
			Struct struct){
		List<Struct> children = struct.children();
		List<ChildRelation> childRelationList = struct.childRelationList();
		boolean isPrevTermTrigger = false;
		for(int i = 0; i < children.size(); i++){
			Struct childStruct = children.get(i);
			String childRelationStr = childRelationList.get(i).childRelationStr();
			FoodStruct childFoodStruct = new FoodStruct(childStruct, childRelationStr);
			//e.g. "to" in "... to apple pie"
			if(ACTION_SOURCE_SET.contains(childRelationStr)){
				actionSourceList.add(childFoodStruct);
				//foodTokenList.add(new FoodToken(childFoodStruct, FoodTokenType.ACTION_SOURCE));
			}else if(ACTION_TARGET_SET.contains(childRelationStr)){
				//only keep one of these lines! Keep first for now.
				actionTargetList.add(childFoodStruct);
				//foodTokenList.add(new FoodToken(childFoodStruct, FoodTokenType.ACTION_TARGET));
			}
			addStructFoodState2(unknownStructList, knownStateList, actionSourceList, actionTargetList, 
					isPrevTermTrigger, childFoodStruct, childRelationStr);
		}
	}
	
	/**
	 * List of current FoodState's in this RecipeGraph.
	 * @return
	 */
	public List<FoodState> currentStateList(){
		return this.currentStateList;
	}
	
	@Override
	public String toString(){
		StringBuilder sb = new StringBuilder();
		sb.append("currentStateList: \n");
		int counter = 0;
		for(FoodState state : currentStateList){
			sb.append(counter++).append(": ").append(state).append("\n");
			sb.append("EXPR:\n").append(state.toExpr()).append("\n");
		}
		return sb.toString();
	}
}
