package thmp.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Scanner;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.TreeMultimap;

import thmp.search.SearchWordPreprocess.WordWrapper;
import thmp.utils.WordForms;

/**
 * Searches by finding intersections in theorems that contain given keywords.
 * 
 * @author yihed
 */
public class SearchIntersection {
	
	//bonus points for matching context better, eg hyp or stm
	//disable bonus now, since not using annotated words
	private static final int CONTEXT_WORD_BONUS = 0;
	
	private static final int NUM_NEAREST_VECS = 4;
	/**
	 * Map of keywords and their scores in document, the higher freq in doc, the lower 
	 * score, say 1/(log freq + 1) since log 1 = 0. 
	 */
	private static final ImmutableMap<String, Integer> wordsScoreMap;	
	
	/**
	 * List of theorems.
	 */
	private static final ImmutableList<String> thmList;
	private static final ImmutableList<String> webDisplayThmList;
	
	/**
	 * Multimap of words, and the theorems (their indices) in thmList, the word shows up in.
	 */
	private static final ImmutableMultimap<String, Integer> wordThmMMap;
	private static final ImmutableMultimap<String, Integer> wordThmMMapNoAnno;
	//these maps are not immutable, they are not modified during runtime.
	private static final Map<String, Integer> twoGramsMap = NGramSearch.get2GramsMap();
	private static final Map<String, Integer> threeGramsMap = ThreeGramSearch.get3GramsMap();
	
	//debug flag for development. Prints out the words used and their scores.
	private static final boolean DEBUG = true;
	private static final boolean anno = false;
	//priority words that should be weighed higher, eg "define"
	//private static final String PRIORITY_WORDS = ConstantsInSearch.get_priorityWords();
	
	/**
	 * Static initializer, builds the maps using CollectThm.java. 
	 */
	static{	
		//System.out.println(thmList);
		wordsScoreMap = CollectThm.ThmWordsMaps.get_wordsScoreMapNoAnno();
		//System.out.println(CollectThm.get_wordsScoreMap());
		wordThmMMap = CollectThm.ThmWordsMaps.get_wordThmsMMap();
		wordThmMMapNoAnno = CollectThm.ThmWordsMaps.get_wordThmsMMapNoAnno();
		//System.out.println(wordsScoreMap);
		thmList = CollectThm.ThmList.get_macroReplacedThmList();
		webDisplayThmList = CollectThm.ThmList.get_webDisplayThmList();
	}
	
	/**
	 * Pair of theorem index and its span score.
	 */
	private static class ThmSpanPair implements Comparable<ThmSpanPair>{
		private int thmIndex;
		private int spanScore;
		
		public ThmSpanPair(int index, int spanScore){
			this.thmIndex = index;
			this.spanScore = spanScore;					
		}
		
		public int thmIndex(){
			return thmIndex;
		}
		
		public int spanScore(){
			return spanScore;
		}
		
		public int compareTo(ThmSpanPair other){
			//return this.spanScore > other.spanScore ? 1 : (this.spanScore < other.spanScore ? -1 : 0);
			//reverse because treemap naturally has ascending order
			//
			return this.spanScore > other.spanScore ? -1 : (this.spanScore < other.spanScore ? 1 : 
				(this == other ? 0 : -1));
		}
	}	
	
	private static class ThmScorePair implements Comparable<ThmScorePair>{
		private int thmIndex;
		private int score;
		
		public ThmScorePair(int index, int score){
			this.thmIndex = index;
			this.score = score;					
		}
		
		public int compareTo(ThmScorePair other){
			//return this.spanScore > other.spanScore ? 1 : (this.spanScore < other.spanScore ? -1 : 0);
			//reverse because treemap naturally has ascending order
			return this.score > other.score ? -1 : (this.score < other.score ? 1 : 0);
		}
	}
	
	public static List<Integer> getHighestThmList(String input, int ... num){
		return getHighestThm(input, num).intersectionVecList();
	}
	
	private static enum TokenType{
		SINGLETON(1), TWOGRAM(2), THREEGRAM(3);
		int indexShift;
		static int TWO_GRAM_BONUS = 1;
		static int THREE_GRAM_BONUS = 1;
		
		TokenType(int shift){
			this.indexShift = shift;
		}
		
		void addToMap(Multimap<Integer, Integer> thmWordSpanMMap, int thmIndex, int wordIndex){
			for(int i = wordIndex; i < wordIndex+indexShift; i++){
				thmWordSpanMMap.put(thmIndex, i);
			}
		}
		
		int adjustNGramScore(int curScoreToAdd, int[] singletonScoresAr, int wordIndex){
			switch(this){
				case SINGLETON:
					return curScoreToAdd;
				case TWOGRAM:
					return Math.max(curScoreToAdd, singletonScoresAr[wordIndex] 
							+ singletonScoresAr[wordIndex+1] + TWO_GRAM_BONUS);
				case THREEGRAM:
					return Math.max(curScoreToAdd, singletonScoresAr[wordIndex] + singletonScoresAr[wordIndex+1]
							+ singletonScoresAr[wordIndex+2] + THREE_GRAM_BONUS);
				default:
					return curScoreToAdd;
			}
		}
		
		/**
		 * Whether current word at wordIndex has been added to thm already under a previous 2/3-gram.
		 * @param thmWordSpanMMap
		 * @param thmIndex
		 * @param wordIndex
		 */
		boolean ifAddedToMap(Multimap<Integer, Integer> thmWordSpanMMap, int thmIndex, int wordIndex){
			
			if(this.equals(THREEGRAM)) return false;
			
			if(this.equals(TWOGRAM)){ 
				return thmWordSpanMMap.containsEntry(thmIndex, wordIndex)
						&& thmWordSpanMMap.containsEntry(thmIndex, wordIndex+1);
			}
			
			if(this.equals(SINGLETON)){ 
				return thmWordSpanMMap.containsEntry(thmIndex, wordIndex);
			}
			
			return false;
		}
		
	}
	//computes singleton scores for words in list
	private static int computeSingletonScores(List<WordWrapper> wordWrapperList, int[] singletonScoresAr){
		int wrapperListSz = wordWrapperList.size();
		int totalSingletonAdded = 0;
		for(int i = 0; i < wrapperListSz; i++){
			String word = wordWrapperList.get(i).word();
			Integer score = wordsScoreMap.get(word);
			if(score == null){
				word = WordForms.getSingularForm(word);
			}
			//singletonScoresAr[i] = score == null ? 0 : score;
			if(score != null){
				singletonScoresAr[i] = score;
				totalSingletonAdded++;
			}else{
				singletonScoresAr[i] = 0;
			}
		}
		return totalSingletonAdded;
	}
	
	/**
	 * Builds scoreThmMMap 
	 * @param input input String
	 * @param numHighest number of highest-scored thms to retrieve.
	 * @return SearchState containing list of indices of highest-scored thms. Sorted in ascending
	 * order, best first. List is 0-based.
	 */
	public static SearchState getHighestThm(String input, int ... num){
		if(input.matches("\\s*")) return null;
		//map containing the indices of theorems added so far, where values are sets (hashset)
		//of indices of words that have been added. This is to reward theorems that cover
		//the more number of words. Actually just use SetMultimap.
		//if 2/3-grams added, add indices of all words in 2/3-gram to set for that thm.
		
		SetMultimap<Integer, Integer> thmWordSpanMMap = HashMultimap.create();
		
		//make input list of words
		//String[] inputAr = input.toLowerCase().split(SPLIT_DELIM);
		List<WordWrapper> wordWrapperList = SearchWordPreprocess.sortWordsType(input);
		
		//create searchState to record the intersectionVecList, and map of tokens and their span scores.
		//
		SearchState searchState = new SearchState();
		
		//determine if first token is integer, if yes, use it as the number of 
		//closest thms. Else use NUM_NEAREST_VECS as default value.
		int numHighest = NUM_NEAREST_VECS;
		//whether to skip first token
		int firstIndex = 0;
		if(num.length != 0){
			numHighest = num[0];
		}
		//user's input overrides default num
		String firstWord = wordWrapperList.get(0).word();
		if(firstWord.matches("\\d+")){
			numHighest = Integer.parseInt(firstWord);
			firstIndex = 1;
		}
		
		/*
		 * Map of theorems, in particular their indices in thmList, and the scores corresponding to the keywords they contain.
		 * The rarer a keyword is in the doc, the higher its score is.
		 */
		Map<Integer, Integer> thmScoreMap = new HashMap<Integer, Integer>();
		//theorem span map, theorem index and their span scores.
		Map<Integer, Integer> thmSpanMap = new HashMap<Integer, Integer>();
		
		/*
		 * Multimap of ints and ints, where key is score, and the value Integers
		 * are the indices of the thms having this score.
		 */
		TreeMultimap<Integer, Integer> scoreThmMMap = TreeMultimap.create();
		
		//total score of all words, used for computing bonus spanning scores, and lowering
		//scores of n-grams if to dominant. Approximate, for instance does not de-singularize.
		//int approxTotalWordsScore = 0;
		int totalWordsScore = 0;
		int numWordsAdded = 0;
		
		//pre-compute approximate total score
	/*	for(int i = firstIndex; i < wordWrapperList.size(); i++){
			WordWrapper curWrapper = wordWrapperList.get(i);
			String word = curWrapper.word();
			approxTotalWordsScore += wordsScoreMap.get(word);
			
			if(i < wordWrapperList.size()-1){				
				String nextWord = wordWrapperList.get(i+1).word();
				word = word + " " + nextWord;
				if(twoGramsMap.containsKey(word)){
					approxTotalWordsScore += wordsScoreMap.get(word);					
				}
				//check for 3 grams. Again only first word is annotated.
				if(i < wordWrapperList.size()-2){
					String thirdWord = wordWrapperList.get(i+2).word();
					word = word + " " + thirdWord;
					if(threeGramsMap.containsKey(word)){
						approxTotalWordsScore += wordsScoreMap.get(word);
					}
				}
			}
		} */
		
		//multimap of words, and the list of thm indices that have been added
		ListMultimap<String, Integer> wordThmIndexMMap = ArrayListMultimap.create();
		
		//map of dominant words and the number of times they've been added, 
		//whose theorem scores might need to be lowered later
		//the words that have been added multiple times in 1, 2-grams and 3-grams
		//values are the number of times they've been added
		//Map<String, Integer> dominantWordsMap = new HashMap<String, Integer>();
		//multimap of indices in wrapper list and the words that start at that index
		Multimap<Integer, String> indexStartingWordsMMap = ArrayListMultimap.create();
		
		int wordWrapperListSz = wordWrapperList.size();
		//array instead of list for lower overhead.
		int[] singletonScoresAr = new int[wordWrapperListSz];
		//pre-compute the scores for singleton words in query
		int totalSingletonAdded = computeSingletonScores(wordWrapperList, singletonScoresAr);
		searchState.set_totalWordAdded(totalSingletonAdded);
		
		//array of words to indicate frequencies that this word was included in either 
		//a singleton or n-gram
		int[] wordCountArray = new int[wordWrapperListSz];
		//whether current word has been included in a singleton or n-gram
		
		for(int i = firstIndex; i < wordWrapperListSz; i++){
			WordWrapper curWrapper = wordWrapperList.get(i);
			String word = curWrapper.word();			
			
			//other annotation form of word. 
			//String wordOtherForm;
			//elicit higher score if wordLong fits
			//also turn into singular form if applicable
			String wordLong = curWrapper.hashToString();
			int scoreAdded = 0;			
		
			//check for 2 grams
			if(i < wordWrapperListSz-1){			
				String nextWord = wordWrapperList.get(i+1).word();
				String nextWordCombined = wordLong + " " + nextWord;
				String twoGram = word + " " + nextWord;				
				
				//check for 3 grams. Again only first word is annotated.
				if(i < wordWrapperListSz-2){
					String thirdWord = wordWrapperList.get(i+2).word();
					String threeWordsCombined = wordLong + " " + thirdWord;
					String threeGram = twoGram + " " + thirdWord;
					if(threeGramsMap.containsKey(threeGram)){
						scoreAdded = addWordThms(thmScoreMap, scoreThmMMap, thmWordSpanMMap, wordThmIndexMMap, curWrapper, threeGram, 
								threeWordsCombined, i, TokenType.THREEGRAM, singletonScoresAr);	
						if(scoreAdded > 0){
							wordCountArray[i] = wordCountArray[i] + 1;
							wordCountArray[i+1] = wordCountArray[i+1] + 1;
							wordCountArray[i+2] = wordCountArray[i+2] + 1;
							totalWordsScore += scoreAdded;
							
							numWordsAdded++;
							indexStartingWordsMMap.put(i, threeGram);
						}
					}
					
				}
				
				if(twoGramsMap.containsKey(twoGram)){
					
					scoreAdded = addWordThms(thmScoreMap, scoreThmMMap, thmWordSpanMMap, wordThmIndexMMap, curWrapper, twoGram, 
							nextWordCombined, i, TokenType.TWOGRAM, singletonScoresAr);	
					
					if(scoreAdded > 0){
						wordCountArray[i] = wordCountArray[i] + 1;
						wordCountArray[i+1] = wordCountArray[i+1] + 1;
						totalWordsScore += scoreAdded;
						searchState.addTokenScore(twoGram, scoreAdded);
						numWordsAdded++;
						indexStartingWordsMMap.put(i, twoGram);
					}
					
				}			
			}
			//if the words in a three gram collectively (3 gram + 2 gram + individual words) weigh a lot, 
			//then scale down the overall words? e.g. "linear map with closed range", "closed", "range", 
			//"closed range" all weigh a lot. Scale proportionally down with respect to the average 
			//score of all words added.
			scoreAdded = addWordThms(thmScoreMap, scoreThmMMap, thmWordSpanMMap, wordThmIndexMMap, curWrapper, 
					word, wordLong, i, TokenType.SINGLETON, singletonScoresAr);
			if(scoreAdded > 0){
				wordCountArray[i] = wordCountArray[i] + 1;
				totalWordsScore += scoreAdded;
				searchState.addTokenScore(word, scoreAdded);
				numWordsAdded++;
				indexStartingWordsMMap.put(i, word);
			}
		}
		
		//System.out.println("BEFORE "+scoreThmMMap);
		//Map<Integer, Integer> g = new HashMap<Integer, Integer>(thmScoreMap);
		//add bonus points to thms with most number of query words, judging from size of value set
		//in thmWordSpanMMap
		addWordSpanBonus(thmScoreMap, scoreThmMMap, thmWordSpanMMap, thmSpanMap, numHighest, ((double)totalWordsScore)/numWordsAdded);
		//System.out.println("AFTER " + g.equals(scoreThmMMap));
		searchState.addThmSpan(thmSpanMap);
		searchState.setThmScoreMap(thmScoreMap);
		
		//lower the thm scores for ones that match words with high wordCountArray counts
		/*lowerThmScores(thmScoreMap, scoreThmMMap, thmWordSpanMMap, wordThmIndexMMap, //dominantWordsMap, 
				indexStartingWordsMMap, wordCountArray, 
				wordWrapperList, ((double)totalWordsScore)/numWordsAdded);*/
		
		//new map to record of the final scores (this obliterates scoreThmMMap)
		//make values into pairs of thms with their span scores
		TreeMultimap<Integer, ThmSpanPair> scoreThmMMap2 = TreeMultimap.create();
		for(Map.Entry<Integer, Integer> thmScoreEntry : thmScoreMap.entrySet()){
			int thmIndex = thmScoreEntry.getKey();
			int thmScore = thmScoreEntry.getValue();
			int spanScore = thmSpanMap.get(thmIndex);
			scoreThmMMap2.put(thmScore, new ThmSpanPair(thmIndex, spanScore));
		}
		
		List<Integer> highestThmList = new ArrayList<Integer>();
		
		//get the thms having the highest k scores. Keys are scores.
		// ****** does this order the value set accordingly as well??
		NavigableMap<Integer, Collection<ThmSpanPair>> thmMap = scoreThmMMap2.asMap().descendingMap();
		
		//pick up numHighest number of unique thms
		Set<Integer> pickedThmSet = new HashSet<Integer>();
		
		int counter = numHighest;
		for(Entry<Integer, Collection<ThmSpanPair>> entry : thmMap.entrySet()){			
			for(ThmSpanPair pair : entry.getValue()){
				Integer thmIndex = pair.thmIndex;
				if(counter == 0) break;				
				if(pickedThmSet.contains(thmIndex)) continue;
				pickedThmSet.add(thmIndex);
				highestThmList.add(thmIndex);		
				counter--;			
				if(DEBUG) {
					System.out.println("thm Score " + entry.getKey() + " thmIndex "+ thmIndex + " thm " 
				+ thmList.get(thmIndex));					
				}
			}
			
		}
		searchState.set_intersectionVecList(highestThmList);
		return searchState;
	}

	/**
	 * Auxiliary method to lower the scores.
	 * if the words in a three gram collectively (3 gram + 2 gram + individual words) weigh a lot,
	 * then scale down the overall words proportionally.
	 * e.g. "linear map with closed range", "closed", "range",
	 * "closed range" all weigh a lot. Scale proportionally down with respect to the average score.
	 * Just reduce token initial scores instead!
	 * @param thmScoreMap
	 * @param scoreThmMMap
	 * @param thmWordSpanMMap
	 * @param wordThmMMap
	 * @param dominantWordsMap
	 * @param indexStartingWordsMMap
	 * @param wordCountArray
	 * @param wordWrapperList
	 * @param avgWordScore
	 */
	private static void lowerThmScores(Map<Integer, Integer> thmScoreMap, TreeMultimap<Integer, Integer> scoreThmMMap,
			Multimap<Integer, Integer> thmWordSpanMMap, ListMultimap<String, Integer> wordThmIndexMMap, 
			Multimap<Integer, String> indexStartingWordsMMap,
			int[] wordCountArray, List<WordWrapper> wordWrapperList, 			
			double avgWordScore){
		
		//if freq above certain level
		for(int i = 0; i < wordCountArray.length; i++){
			
			//String word = wordWrapperList.get(i).word();
			//dominant map
			if(wordCountArray[i] > 1){
				//set of words that start at this index
				Collection<String> indexWordsCol = indexStartingWordsMMap.get(i);
				
				for(String indexWord : indexWordsCol){
					String[] wordAr = indexWord.split(" ");
					int len = indexWord.split(" ").length;
					//and score above averg
					if(len == 1 && wordsScoreMap.get(indexWord) > avgWordScore*3.0/2){
						adjustWordClusterScore(thmScoreMap, scoreThmMMap, wordThmIndexMMap, avgWordScore, indexWord);												
					}else if(len == 2){
						//2 tuple, only lower if second word also included often with high score
						if(wordsScoreMap.get(wordAr[1]) > avgWordScore*3.0/2 && wordCountArray[i+1] > 1){
							adjustWordClusterScore(thmScoreMap, scoreThmMMap, wordThmIndexMMap, avgWordScore, indexWord);							
						}
					}else if(len == 3){
						//adjust score only if either the second or third word gets counted multiple times, and weigh
						//more than 3/2 of the average score.
						if(wordsScoreMap.get(wordAr[1]) > avgWordScore*3.0/2 && wordCountArray[i+1] > 1 
								|| wordsScoreMap.get(wordAr[2]) > avgWordScore*3.0/2 && wordCountArray[i+2] > 1){
							adjustWordClusterScore(thmScoreMap, scoreThmMMap, wordThmIndexMMap, avgWordScore, indexWord);
						}
					}
					
				}
				
			}
			
		}
		
	}

	/**
	 * Auxiliary method to adjust scores of word clusters. 
	 * @param thmScoreMap
	 * @param scoreThmMMap
	 * @param wordThmMMap
	 * @param avgWordScore
	 * @param indexWord word whose score is being reduced
	 */
	private static void adjustWordClusterScore(Map<Integer, Integer> thmScoreMap,
			TreeMultimap<Integer, Integer> scoreThmMMap, ListMultimap<String, Integer> wordThmIndexMMap, double avgWordScore,
			String indexWord) {
		//get list of theorems
		List<Integer> thmList = wordThmIndexMMap.get(indexWord);
		int prevWordScore = wordsScoreMap.get(indexWord);
		int scoreToDeduct = (int)(prevWordScore-avgWordScore/3.0);
		System.out.println("word being deducted: " + indexWord + " score being deducted " + scoreToDeduct);
		
		//lower their scores
		for(int thmIndex : thmList){
			int prevScore = thmScoreMap.get(thmIndex);
			//removing the highest might not be enough! There might be other score entries 
			//for this thm already that's higher than the new score.
			//scoreThmMMap.remove(prevScore, thmIndex);
			int newThmScore = prevScore - scoreToDeduct;
			//customize this score more based on avg score
			scoreThmMMap.put(newThmScore, thmIndex);
			thmScoreMap.put(thmIndex, newThmScore);
		}
	}
	
	/**
	 * Auxiliary method to add bonus points to theorems containing more words.
	 * Bonus is proportional to the highest thm score,
	 * @param thmScoreMap
	 * @param scoreThmMMap
	 * @param thmWordSpanMMap
	 * @param thmSpanMap map of theorem indices and their spans
	 */
	private static void addWordSpanBonus(Map<Integer, Integer> thmScoreMap, TreeMultimap<Integer, Integer> scoreThmMMap,
			SetMultimap<Integer, Integer> thmWordSpanMMap, Map<Integer, Integer> thmSpanMap, int N, double avgWordScore){
		//add  according to score
		//gather the sizes of the value maps for thmWordSpanMMap, and keep track of order based on scores using a TreeMultimap		
		TreeMultimap<Integer, Integer> spanScoreThmMMap = TreeMultimap.create();
		
		for(int thmIndex : thmWordSpanMMap.keySet()){
			//System.out.println(thmWordSpanMMap.get(thmIndex));
			int thmWordsSetSize = thmWordSpanMMap.get(thmIndex).size();
			thmSpanMap.put(thmIndex, thmWordsSetSize);
			spanScoreThmMMap.put(thmWordsSetSize, thmIndex);
		}
		//add bonus proportional to the avg word score (not span score)
		NavigableMap<Integer, Collection<Integer>> r = spanScoreThmMMap.asMap().descendingMap();
		
		int counter = N;
		//counts which span level is being iterated over currently
		int spanCounter = 1;		
		for(Entry<Integer, Collection<Integer>> entry : r.entrySet()){	
			
			for(int thmIndex : entry.getValue()){				
				if(counter == 0) break;
				int prevScore = thmScoreMap.get(thmIndex);
				//refine this! using the highest or average score (not span score) *******	
				int newThmScore = (int)(prevScore + avgWordScore/(double)spanCounter);
				scoreThmMMap.put(newThmScore, thmIndex);
				thmScoreMap.put(thmIndex, newThmScore);
				if(DEBUG){ 
					String thm = thmList.get(thmIndex);
					System.out.println("theorem whose score is upped. size "+ entry.getKey() + " newThmScore: " + newThmScore+ " thm: " + thm);
					//System.out.println("PREV SCORE " + prevScore + " NEW SCORE " + newThmScore + thm);
				}
				counter--;
			}
			spanCounter++;
		}
		
	}
	
	/**
	 * Auxiliary method for getHighestVecs. Retrieves thms that contain wordLong,
	 * add these thms to map. Annotated 2 grams only have annotation at start of
	 * first word.
	 * @param thmScoreMap
	 * @param scoreThmMMap
	 * @param curWrapper
	 * @param word
	 * @param wordLong
	 * @param wordIndices array of indices of words in query
	 * @param singletonScoresAr Array of scores for singleton words
	 * @return List of theorem indices that have been added, 
	 */
	private static int addWordThms(Map<Integer, Integer> thmScoreMap, TreeMultimap<Integer, Integer> scoreThmMMap,
			Multimap<Integer, Integer> thmWordSpanMMap, ListMultimap<String, Integer> wordThmIndexMMap, //Map<String, Integer> dominantWordsMap,
			WordWrapper curWrapper, String word, String wordLong, int wordIndex, TokenType tokenType, int[] singletonScoresAr) {		
		//update scores map
		int curScoreToAdd = 0;
		int scoreAdded = 0;
		//for every word, get list of thms containing this word	
		Collection<Integer> wordThms;
		if(anno){
			wordThms = wordThmMMap.get(wordLong);
		}else{
			wordThms = wordThmMMapNoAnno.get(word);
		}
		Integer wordScore = 0;
		//System.out.println("word " + word);
		
		if(!wordThms.isEmpty()){	
			//wordScore = wordsScoreMap.get(wordLong);
			wordScore = wordsScoreMap.get(word);	
			wordScore = wordScore == null ? 0 : wordScore;
			curScoreToAdd = wordScore + CONTEXT_WORD_BONUS 
					+ curWrapper.matchExtraPoints();
			
			/*if(debug){
				System.out.println("first time Word added: " + word + ". Score: " + curScoreToAdd);
			}*/
		}else{
			//String wordOtherForm = curWrapper.otherHashForm();
			//String singWordOtherForm = curWrapper.otherHashForm();
			
			String singForm = WordForms.getSingularForm(word);	
			String singFormLong = curWrapper.hashToString(singForm);
			//if(wordsScoreMap.get(singFormLong) != null){
			if(wordsScoreMap.get(singForm) != null){	
				if(anno){
					wordThms = wordThmMMap.get(singFormLong);
				}else{
					wordThms = wordThmMMapNoAnno.get(singForm);
				}
				//wordScore = wordsScoreMap.get(singFormLong);
				wordScore = wordsScoreMap.get(singForm);
				wordScore = wordScore == null ? 0 : wordScore;				
				curScoreToAdd = wordScore + CONTEXT_WORD_BONUS 
						+ curWrapper.matchExtraPoints();
				word = singForm;
				/*if(debug){
					System.out.println("Word added: " + word + ". Score: " + curScoreToAdd);
				}*/
			}//other form of word
			/*else if(wordThmMMap.containsKey(wordOtherForm)){
				wordThms = wordThmMMap.get(wordOtherForm);
				wordScore = wordsScoreMap.get(wordOtherForm);
				curScoreToAdd = wordScore;				
			}else if(wordThmMMap.containsKey(singWordOtherForm)){
				wordThms = wordThmMMap.get(singWordOtherForm);
				wordScore = wordsScoreMap.get(singWordOtherForm);
				curScoreToAdd = wordScore;		
			}			*/	
		}
		//adjust curScoreToAdd, boost 2/3-gram scores when applicable
		curScoreToAdd = tokenType.adjustNGramScore(curScoreToAdd, singletonScoresAr, wordIndex);
		
		if(wordThms != null && curScoreToAdd != 0){
			//System.out.println("wordThms " + wordThms);
			wordThmIndexMMap.putAll(word, wordThms);
			if(DEBUG){
				System.out.println("Word added: " + word + ". Score: " + curScoreToAdd);
			}
			for(Integer thmIndex : wordThms){
				//skip thm if current word already been covered by previous 2/3-gram
				if(tokenType.ifAddedToMap(thmWordSpanMMap, thmIndex, wordIndex)) continue;
				Integer prevScore = thmScoreMap.get(thmIndex);
				prevScore = prevScore == null ? 0 : prevScore;
				Integer newScore = prevScore + curScoreToAdd;
				//this mapping is not being used in the end right now,
				//since the top N are picked, regardless of their scores.
				thmScoreMap.put(thmIndex, newScore);
				//System.out.println("*** " + thmScoreMap);
				scoreThmMMap.put(newScore, thmIndex);
				//put in thmIndex, and the index of word in the query, to thmWordSpanMMap.				
				tokenType.addToMap(thmWordSpanMMap, thmIndex, wordIndex);
				
				scoreAdded = curScoreToAdd;				
			}				
		}
		return scoreAdded;
	}	
	
	/**
	 * Searches the theorem base using just the intersection algorithm.
	 * @param inputStr Query string.
	 * @return
	 */
	public static List<String> search(String inputStr){
		
		List<String> foundThmList = new ArrayList<String>();

		List<Integer> highestThms = getHighestThmList(inputStr);
		
		if(highestThms == null){
			foundThmList.add("Close, but no cigar. I don't have a theorem on that yet.");
			//return thmList;
			return null;
		}
		
		for(Integer thmIndex : highestThms){
			//foundThmList.add(thmList.get(thmIndex));
			foundThmList.add(webDisplayThmList.get(thmIndex));			
		}
		
		return foundThmList;
	}
	
	/**
	 * Reads in keywords. Gets theorems with highest scores for this.
	 * @param args
	 */
	public static void main(String[] args){
		Scanner sc = new Scanner(System.in);
		boolean contextSearch = true;
		
		while(sc.hasNextLine()){
			String thm = sc.nextLine();			
			
			List<Integer> highestThms = getHighestThmList(thm);
			
			if(highestThms == null) continue;
			
			if(contextSearch){
				highestThms = ContextSearch.contextSearch(thm, highestThms);
			}
			
			for(Integer thmIndex : highestThms){
				System.out.println(thmList.get(thmIndex));
			}
		}		
		sc.close();
	}	

}
