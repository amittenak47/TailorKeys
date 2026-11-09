# The autocorrect prototype

TailorKeys' first autocorrect model went into the keyboard on 1 November 2026. It is retired and no longer in this
repository. This page records how it worked, where it fell short, and what it taught us.

## How it worked

- **The model:** a 51-million-parameter transformer (11 layers, its own vocabulary of 8,192 word pieces), trained from
  scratch on web, chat, encyclopedia and Q&A text with simulated typing. It shipped as a 52 MB int8 ONNX graph run on
  the phone's CPU with ONNX Runtime.
- **Each word:** when a word ended at the space bar, a tap search listed the words the taps could have meant (usually
  12 to 25, plus the word as typed). The model scored each one and the best was written. Once the next words existed,
  each word was checked again and changed only if it now looked more likely wrong than right. One backspace undid any
  change.
- **How it read:** left to right only, with up to 64 word pieces before the word and 32 after.

## Results

| Measure | Result |
|---|---|
| Typing without looking at the keys (60 paragraphs, 5,818 words) | Best 70.1% of words right. The goal was 88%, and 99% is what feels usable |
| Real-word slips fixed (by/buy and the like) | 501 of 517 |
| Everyday typos fixed | 858 of 876 |
| Correctly typed words it changed | 12 to 21 of 11,437 |
| Listed misspellings fixed | 69 to 71% |
| Skipped, doubled and swapped letters in common words | 95 to 99% |
| A clean tap on the wrong key | 71 to 73% |
| Names with one wrong letter | 15% |
| Punctuation after the word | Periods about half the time, commas rarely, `! : ;` never |
| Time from the space bar to the fix on a tablet | 0.4 to 0.6 seconds |

The 70.1% was never confirmed on paragraphs held back from all choices, so it may flatter the model.

## Where it fell short

1. **It could only pick from the search's list.** When the search didn't offer the right word, nothing could fix it:
   *definately* never got *definitely*, 16% of listed misspellings never had their correction offered, and names with
   one wrong letter were offered only 15% of the time.
2. **It didn't know rare words.** 51M parameters that had read about 5 billion word pieces (about 100 per parameter)
   learned common English well and rare English poorly. Misspellings of words past roughly the 5,000th most common were
   often kept even when the correction was offered. About 15 correctly typed rare words (*derives*, *gilding*,
   *canyons*) sat right at the edge and flipped with any change to training.
3. **It leaned on the taps more than the sentence.** Reading left to right, with only a short window after the word,
   it kept clean real-word slips: *want to by a car* stayed, at 95% confidence.
4. **It learned the simulator's habits, not people's.** Training's wrong-key slips landed off-centre, so a clean tap
   dead on a wrong key read as deliberate. Clean taps on a doubled letter did too: *lazzy* was kept at 94%.
5. **Learning from a bigger model made it worse.** Training it to copy a much larger reference model's judgements
   lowered every score and more than doubled the correct words it changed (14 against 6).
6. **New skills cost old ones.** Each new kind of mistake added to its training mix (space slips, stray digits,
   casual spellings) raised the count of correct words it changed, from 14 to 19 to 24, until extra training pulled
   it back.
7. **Some inputs were never connected.** The keyboard always sent default values for two of the model's inputs
   (where each tap came from, and the casual-spellings setting).
8. **The keyboard dropped some fixes.** Fast typing skipped the second check, and late fixes were thrown away after
   48 more characters. Both were fixed in the keyboard during the prototype's run.

## What we learned

- **Whether the right word is offered sets the ceiling.** Measure how often the search offers the right answer apart
  from how often the model picks it. No model can choose a word it is never shown.
- **Language knowledge needs scale.** A small model trained from scratch handles common words and struggles with rare
  ones, names and real-word slips, which are the cases autocorrect exists for.
- **Context after the word matters.** In our tests a model that saw both sides of a word did far better than one that
  saw only the words before it.
- **Copying a bigger model's opinions is not a shortcut.** Here it made the small model worse at everything.
- **Training data has to look like real mistakes.** Clean taps on a wrong key, doubled letters and real-word slips
  must all be in the training data, each next to matching examples that must be left alone.
- **Every training change needs a guard.** Keep a fixed set of correctly typed words, rare ones included, and count
  how many each change alters. A mix chosen at random traded one skill for another.
- **Test the way people type.** Whole paragraphs typed without looking, on text the model never saw while training
  or while choosing between versions, and timing measured on the tablet itself.
