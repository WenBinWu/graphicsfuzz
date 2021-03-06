/*
 * Copyright 2018 The GraphicsFuzz Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.graphicsfuzz.reducer.tool;

import com.graphicsfuzz.common.glslversion.ShadingLanguageVersion;
import com.graphicsfuzz.common.transformreduce.Constants;
import com.graphicsfuzz.common.util.FileHelper;
import com.graphicsfuzz.common.util.Helper;
import com.graphicsfuzz.common.util.IRandom;
import com.graphicsfuzz.common.util.IdGenerator;
import com.graphicsfuzz.common.util.ParseTimeoutException;
import com.graphicsfuzz.common.util.RandomWrapper;
import com.graphicsfuzz.common.util.ReductionStepHelper;
import com.graphicsfuzz.reducer.IFileJudge;
import com.graphicsfuzz.reducer.IReductionStateFileWriter;
import com.graphicsfuzz.reducer.ReductionDriver;
import com.graphicsfuzz.reducer.ReductionKind;
import com.graphicsfuzz.reducer.filejudge.FuzzingFileJudge;
import com.graphicsfuzz.reducer.filejudge.ImageGenErrorShaderFileJudge;
import com.graphicsfuzz.reducer.filejudge.ImageShaderFileJudge;
import com.graphicsfuzz.reducer.filejudge.ValidatorErrorShaderFileJudge;
import com.graphicsfuzz.reducer.glslreducers.GlslReductionState;
import com.graphicsfuzz.reducer.glslreducers.GlslReductionStateFileWriter;
import com.graphicsfuzz.reducer.reductionopportunities.ReductionOpportunityContext;
import com.graphicsfuzz.server.thrift.FuzzerServiceManager;
import com.graphicsfuzz.shadersets.ExactImageFileComparator;
import com.graphicsfuzz.shadersets.HistogramImageFileComparator;
import com.graphicsfuzz.shadersets.IShaderDispatcher;
import com.graphicsfuzz.shadersets.LocalShaderDispatcher;
import com.graphicsfuzz.shadersets.RemoteShaderDispatcher;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Reduce {

  private static final Logger LOGGER = LoggerFactory.getLogger(Reduce.class);

  private static ArgumentParser getParser() {

    ArgumentParser parser = ArgumentParsers.newArgumentParser("Reduce")
          .defaultHelp(true)
          .description("Reduce a shader, driven by a criterion of interest.");

    // Required arguments
    parser.addArgument("fragment_shader")
          .help("Path of fragment shader to be reduced.")
          .type(File.class);

    parser.addArgument("reduction_kind")
          .help("Kind of reduction to be performed.  Options are:\n"
                + "   " + ReductionKind.NO_IMAGE
                + "               Reduces while image generation fails to produce an image.\n"
                + "   " + ReductionKind.NOT_IDENTICAL
                + "       Reduces while produced image is not identical to reference.\n"
                + "   " + ReductionKind.IDENTICAL
                + "           Reduces while produced image is identical to reference.\n"
                + "   " + ReductionKind.BELOW_THRESHOLD
                + "     Reduces while histogram difference between produced image and "
                + "reference is below a threshold.\n"
                + "   " + ReductionKind.ABOVE_THRESHOLD
                + "     Reduces while histogram difference between produced image and "
                + "reference is above a threshold.\n"
                + "   " + ReductionKind.VALIDATOR_ERROR
                + "     Reduces while validator gives a particular error\n"
                + "   " + ReductionKind.ALWAYS_REDUCE
                + "       Always reduces (useful for testing)\n")
          .type(String.class);

    parser.addArgument("--reference_image")
          .help("Path to reference image for comparisons.")
          .type(File.class);

    parser.addArgument("--threshold")
          .help("Threshold used for histogram differencing.")
          .setDefault(100.0)
          .type(Double.class);

    parser.addArgument("--timeout")
          .help(
                "Time in seconds after which execution of an individual variant is terminated "
                      + "during reduction.")
          .setDefault(30)
          .type(Integer.class);

    parser.addArgument("--max_steps")
          .help(
                "The maximum number of reduction steps to take before giving up and outputting the "
                      + "final reduced file.")
          .setDefault(250)
          .type(Integer.class);

    parser.addArgument("--retry_limit")
          .help("When getting an image via the server, the number of times the server should "
                + "allow the client to retry a shader before assuming the shader crashes the "
                + "client and marking it as SKIPPED.")
          .setDefault(2)
          .type(Integer.class);

    parser.addArgument("--verbose")
          .help("Emit detailed information related to the reduction process.")
          .action(Arguments.storeTrue());

    parser.addArgument("--skip_render")
          .help("Don't render the shader on remote clients. Useful when reducing compile or link "
                + "errors.")
          .action(Arguments.storeTrue());

    parser.addArgument("--seed")
          .help("Seed to initialize random number generator with.")
          .setDefault(new Random().nextInt())
          .type(Integer.class);

    parser.addArgument("--error_string")
          .help("String checked for containment in validation or compilation tool error message.")
          .type(String.class);

    parser.addArgument("--server")
          .help("Server URL to which image jobs are sent.")
          .type(String.class);

    parser.addArgument("--token")
          .help("Client token to which image jobs are sent. Used with --server.")
          .type(String.class);

    parser.addArgument("--output")
          .help("Output directory.")
          .setDefault(new File("."))
          .type(File.class);

    parser.addArgument("--reduce_everywhere")
          .help("Allow reducer to reduce arbitrarily.")
          .action(Arguments.storeTrue());

    parser.addArgument("--stop_on_error")
          .help("Quit if something goes wrong during reduction; useful for testing.")
          .action(Arguments.storeTrue());

    parser.addArgument("--swiftshader")
          .help("Use swiftshader for rendering.")
          .action(Arguments.storeTrue());

    parser.addArgument("--continue_previous_reduction")
          .help("Carry on from where a previous reduction attempt left off.")
          .action(Arguments.storeTrue());

    return parser;

  }

  public static void main(String[] args) {
    try {
      mainHelper(args, null);
    } catch (ArgumentParserException exception) {
      exception.getParser().handleError(exception);
      System.exit(1);
    } catch (Throwable ex) {
      ex.printStackTrace();
      System.exit(1);
    }
  }

  public static void mainHelper(
        String[] args,
        FuzzerServiceManager.Iface managerOverride)
        throws ArgumentParserException, IOException, ParseTimeoutException {

    ArgumentParser parser = getParser();

    Namespace ns = parser.parseArgs(args);

    final File workDir = ns.get("output");

    // Create output dir
    FileUtils.forceMkdir(workDir);

    try {
      if (ns.get("reduction_kind").equals(ReductionKind.VALIDATOR_ERROR)
            && ns.get("error_string") == null) {
        throw new ArgumentParserException(
              "If reduction kind is "
                    + ReductionKind.VALIDATOR_ERROR
                    + " then --error_string must be provided.",
              parser);
      }

      final File vertexShader = ns.get("vertex_shader");

      ReductionKind reductionKind = null;
      try {
        reductionKind = ReductionKind.valueOf(((String) ns.get("reduction_kind")).toUpperCase());
      } catch (IllegalArgumentException exception) {
        throw new ArgumentParserException(
              "unknown reduction kind argument found: " + ns.get("reduction_kind"),
              parser);
      }

      final double threshold = ns.get("threshold");
      // TODO: integrate timeout into reducer
      @SuppressWarnings("UnusedAssignment") Integer timeout = ns.get("timeout");
      final Integer maxSteps = ns.get("max_steps");
      final Integer retryLimit = ns.get("retry_limit");
      final Boolean verbose = ns.get("verbose");
      final boolean skipRender = ns.get("skip_render");
      final int seed = ns.get("seed");
      final String errorString = ns.get("error_string");
      final boolean reduceEverywhere = ns.get("reduce_everywhere");
      final boolean stopOnError = ns.get("stop_on_error");

      final String server = ns.get("server");
      final String token = ns.get("token");

      final Boolean usingSwiftshader = ns.get("swiftshader");

      final Boolean continuePreviousReduction = ns.get("continue_previous_reduction");

      if (managerOverride != null && (server == null || token == null)) {
        throw new ArgumentParserException(
              "Must supply server (dummy string) and token when executing in server process.",
              parser);
      }

      if (server != null && token == null) {
        throw new ArgumentParserException("If --server is used then --token is required", parser);
      }
      if (server == null && token != null) {
        LOGGER.warn("Warning: --token ignored, as it is used without --server");
      }
      if (server != null && usingSwiftshader) {
        LOGGER.warn("Warning: --swiftshader ignored, as --server is being used");
      }

      File fragmentShader = ns.get("fragment_shader");
      File referenceImage = ns.get("reference_image");

      // Check input files
      FileHelper.checkExists(fragmentShader);
      FileHelper.checkExists(FileHelper.replaceExtension(fragmentShader, ".json"));
      FileHelper.checkExistsOrNull(referenceImage);
      if (continuePreviousReduction) {
        FileHelper.checkExists(new File(workDir, Constants.REDUCTION_INCOMPLETE));
      }

      // Copy input files to output dir (unless they are in there already), and update variables.
      File temp;

      if (!FileUtils.directoryContains(workDir, fragmentShader)) {
        temp = fragmentShader;
        fragmentShader = Paths.get(workDir.toString(), fragmentShader.getName()).toFile();
        FileUtils.copyFile(temp, fragmentShader);
        FileUtils.copyFile(
              FileHelper.replaceExtension(temp, ".json"),
              FileHelper.replaceExtension(fragmentShader, ".json")
        );
      }

      if (referenceImage != null && !FileUtils.directoryContains(workDir, referenceImage)) {
        temp = referenceImage;
        referenceImage = Paths.get(workDir.toString(), "reference_image.png").toFile();
        FileUtils.copyFile(temp, referenceImage);
      }

      IFileJudge fileJudge;

      IShaderDispatcher imageGenerator =

            server == null || server.isEmpty() || server.equals(".")
                  ? new LocalShaderDispatcher(usingSwiftshader)
                  : new RemoteShaderDispatcher(
                        server + "/manageAPI",
                        token,
                        managerOverride,
                        new AtomicLong(),
                        retryLimit);

      File corpus = new File(workDir, "corpus");

      switch (reductionKind) {
        case NO_IMAGE:
          fileJudge =
                new ImageGenErrorShaderFileJudge(
                      workDir,
                      (errorString == null || errorString.isEmpty()) ? null
                            : Pattern.compile(".*" + errorString + ".*", Pattern.DOTALL),
                      imageGenerator,
                      skipRender,
                      stopOnError);
          break;
        case NOT_IDENTICAL:
          fileJudge = new ImageShaderFileJudge(null, referenceImage, workDir,
                new ExactImageFileComparator(false),
                imageGenerator,
                stopOnError);
          break;
        case IDENTICAL:
          fileJudge = new ImageShaderFileJudge(null, referenceImage, workDir,
                new ExactImageFileComparator(true),
                imageGenerator,
                stopOnError);
          break;
        case BELOW_THRESHOLD:
          fileJudge = new ImageShaderFileJudge(null, referenceImage, workDir,
                new HistogramImageFileComparator(threshold, false),
                imageGenerator,
                stopOnError);
          break;
        case ABOVE_THRESHOLD:
          fileJudge = new ImageShaderFileJudge(null, referenceImage, workDir,
                new HistogramImageFileComparator(threshold, true),
                imageGenerator,
                stopOnError);
          break;
        case VALIDATOR_ERROR:
          fileJudge = new ValidatorErrorShaderFileJudge(errorString.isEmpty() ? null
                : Pattern.compile(".*" + errorString + ".*", Pattern.DOTALL));
          break;
        case ALWAYS_REDUCE:
          fileJudge = item -> true;
          break;
        case FUZZ:
          fileJudge = new FuzzingFileJudge(workDir, corpus, imageGenerator);
          break;
        default:
          throw new ArgumentParserException(
                "Unsupported reduction kind: " + reductionKind,
                parser);
      }

      doReductionHelper(
            fragmentShader,
            seed,
            fileJudge,
            workDir,
            maxSteps,
            reduceEverywhere,
            continuePreviousReduction,
            verbose);

    } catch (Throwable ex) {

      File fragmentShader = ns.get("fragment_shader");
      final String exceptionFilename = FilenameUtils.removeExtension(FilenameUtils
            .getBaseName(fragmentShader.getAbsolutePath())) + "_exception.txt";

      FileUtils.writeStringToFile(
            new File(workDir, exceptionFilename),
            ExceptionUtils.getStackTrace(ex),
            Charset.defaultCharset()
      );

      throw ex;
    }
  }

  public static void doReductionHelperSafe(
        File fragmentShader,
        int seed,
        IFileJudge fileJudge,
        File workDir,
        int stepLimit,
        boolean reduceEverywhere,
        boolean continuePreviousReduction,
        boolean verbose) {
    try {
      doReductionHelper(fragmentShader, seed, fileJudge, workDir, stepLimit, reduceEverywhere,
            continuePreviousReduction,
            verbose);
    } catch (Exception ex) {
      try {
        FileUtils.writeStringToFile(
              new File(workDir,
                    FilenameUtils.removeExtension(fragmentShader.getName()) + "_exception.txt"),
              ExceptionUtils.getStackTrace(ex), Charset.defaultCharset());
      } catch (IOException exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  public static void doReductionHelper(
        File fragmentShader,
        int seed,
        IFileJudge fileJudge,
        File workDir,
        int stepLimit,
        boolean reduceEverywhere,
        boolean continuePreviousReduction,
        boolean verbose)
        throws IOException, ParseTimeoutException {
    final ShadingLanguageVersion shadingLanguageVersion =
        ShadingLanguageVersion.getGlslVersionFromShader(fragmentShader);
    final IRandom random = new RandomWrapper(seed);
    final IdGenerator idGenerator = new IdGenerator();

    final int fileCountOffset = getFileCountOffset(fragmentShader, workDir,
          continuePreviousReduction);
    final File startingShaderFile = getStartingShaderFile(fragmentShader, workDir,
          continuePreviousReduction);

    if (continuePreviousReduction) {
      assert new File(workDir, Constants.REDUCTION_INCOMPLETE).exists();
      new File(workDir, Constants.REDUCTION_INCOMPLETE).delete();
    }

    GlslReductionState initialState = new GlslReductionState(
          Optional.of(Helper.parse(startingShaderFile, true)));
    IReductionStateFileWriter fileWriter = new GlslReductionStateFileWriter(
        shadingLanguageVersion);

    new ReductionDriver(new ReductionOpportunityContext(
          reduceEverywhere,
        shadingLanguageVersion,
          random,
          idGenerator), verbose, initialState)
          .doReduction(FilenameUtils.removeExtension(fragmentShader.getAbsolutePath()),
                fileCountOffset,
                fileWriter,
                fileJudge,
                workDir,
                stepLimit);
  }

  private static File getStartingShaderFile(File fragmentShader, File workDir,
        boolean continuePreviousReduction) {
    if (!continuePreviousReduction) {
      return fragmentShader;
    }
    final int latestSuccessfulReduction = ReductionStepHelper
          .getLatestReductionStepSuccess(workDir,
                FilenameUtils.removeExtension(fragmentShader.getName())).orElse(0);
    if (latestSuccessfulReduction == 0) {
      return fragmentShader;
    }
    return new File(workDir, ReductionDriver.getReductionStepFilenamePrefix(
            FilenameUtils.removeExtension(fragmentShader.getName()),
            latestSuccessfulReduction, Optional.of("success")) + ".frag");
  }

  private static int getFileCountOffset(File fragmentShader, File workDir,
        boolean continuePreviousReduction) {
    if (!continuePreviousReduction) {
      return 0;
    }
    return ReductionStepHelper.getLatestReductionStepAny(workDir,
          FilenameUtils.removeExtension(fragmentShader.getName())).orElse(0);
  }

}
