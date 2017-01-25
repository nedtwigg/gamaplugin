/*********************************************************************************************
 *
 * 'GenstarCreateDelegate.java, in plugin ummisco.gaml.extensions.genstar, is part of the source code of the GAMA
 * modeling and simulation platform. (c) 2007-2016 UMI 209 UMMISCO IRD/UPMC & Partners
 *
 * Visit https://github.com/gama-platform/gama for license information and developers contact.
 *
 *
 **********************************************************************************************/
package genstar.plugin;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.metamodel.pop.APopulationAttribute;
import core.metamodel.pop.APopulationEntity;
import core.metamodel.pop.APopulationValue;
import core.util.data.GSEnumDataType;

import gospl.GosplPopulation;
import gospl.algo.ISyntheticReconstructionAlgo;
import gospl.algo.IndependantHypothesisAlgo;
import gospl.algo.sampler.IDistributionSampler;
import gospl.algo.sampler.ISampler;
import gospl.algo.sampler.sr.GosplBasicSampler;
import gospl.distribution.GosplDistributionBuilder;
import gospl.distribution.exception.IllegalControlTotalException;
import gospl.distribution.exception.IllegalDistributionCreation;
import gospl.distribution.matrix.INDimensionalMatrix;
import gospl.distribution.matrix.coordinate.ACoordinate;
import gospl.entity.attribute.value.RangeValue;
import gospl.entity.attribute.value.UniqueValue;
import gospl.generator.DistributionBasedGenerator;
import gospl.generator.ISyntheticGosplPopGenerator;
import gospl.io.exception.InvalidSurveyFormatException;
import msi.gama.common.interfaces.ICreateDelegate;

import msi.gama.runtime.IScope;
import msi.gama.util.file.GamaXMLFile;
import msi.gaml.operators.Cast;
import msi.gaml.statements.Arguments;
import msi.gaml.statements.CreateStatement;
import msi.gaml.types.IType;
import msi.gaml.types.Types;

public class GenstarCreateDelegate implements ICreateDelegate {

    public GenstarCreateDelegate() {
        System.out.println("Delegate created");
    }

    @Override
    public boolean acceptSource(final IScope scope, final Object source) {
        return source instanceof GamaXMLFile && ((GamaXMLFile) source).getRootTag(scope).equals("GosplConfiguration");
    }

    @Override
    public boolean createFrom(final IScope scope, final List<Map<String, Object>> inits, final Integer max,
                              final Object source, final Arguments init, final CreateStatement statement) {

        final GamaXMLFile file = (GamaXMLFile) source;

        // INPUT ARGS
        // If no population size is specified, 1 is taken as a default.
        // TODO See if it is possible to infer a default population number from the data.
        final int targetPopulation = max == null ? 1 : max;

        scope.getGui().getStatus().beginSubStatus("Generating " + targetPopulation + " agents");
        final Path confFile = Paths.get(file.getPath(scope));

        // THE POPULATION TO BE GENERATED
        GosplPopulation population = null;

        // INSTANCIATE FACTORY
        GosplDistributionBuilder df = null;

        try {
            df = new GosplDistributionBuilder(confFile);
        } catch (final FileNotFoundException e) {
            e.printStackTrace();
        }

        // RETRIEV INFORMATION FROM DATA IN FORM OF A SET OF JOINT DISTRIBUTIONS
        try {
            df.buildDistributions();
        } catch (InvalidFormatException | IOException | InvalidSurveyFormatException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        }

        // TRANSPOSE SAMPLES INTO IPOPULATION
        // TODO: yet to be tested
        try {
            df.buildSamples();
        } catch (InvalidFormatException | IOException | InvalidSurveyFormatException e2) {
            // TODO Auto-generated catch block
            e2.printStackTrace();
        }

        // HERE IS A CHOICE TO MAKE BASED ON THE TYPE OF GENERATOR WE WANT:
        // Choice is made here to use distribution based generator

        // so we collapse all distribution build from the data
        INDimensionalMatrix<APopulationAttribute, APopulationValue, Double> distribution = null;
        try {
            distribution = df.collapseDistributions();
        } catch (final IllegalDistributionCreation | IllegalControlTotalException e1) {
            e1.printStackTrace();
        }

        // BUILD THE SAMPLER WITH THE INFERENCE ALGORITHM
        final ISyntheticReconstructionAlgo<IDistributionSampler> distributionInfAlgo = new IndependantHypothesisAlgo();
        ISampler<ACoordinate<APopulationAttribute, APopulationValue>> sampler = null;
        try {
            sampler = distributionInfAlgo.inferSRSampler(distribution, new GosplBasicSampler());
        } catch (final IllegalDistributionCreation e1) {
            e1.printStackTrace();
        }

        // BUILD THE GENERATOR
        final ISyntheticGosplPopGenerator ispGenerator = new DistributionBasedGenerator(sampler);

        // BUILD THE POPULATION
        try {
            population = ispGenerator.generate(targetPopulation);
        } catch (final NumberFormatException e) {
            e.printStackTrace();
        }

        final Collection<APopulationAttribute> attributes = population.getPopulationAttributes();
        double index = 0;
        for (final APopulationEntity e : population) {
            scope.getGui().getStatus().setSubStatusCompletion(index++ / targetPopulation);
            final Map<String, Object> agent = new HashMap<String, Object>();
            for (final APopulationAttribute attribute : attributes) {
                final String name = attribute.getAttributeName();
                agent.put(name, getAttributeValue(scope, e, attribute));
            }
            // scope.getSimulation().getProjectionFactory().agent.put(IKeyword.SHAPE, new GamaShape(e.getLocation()));
            statement.fillWithUserInit(scope, agent);
            inits.add(agent);
        }
        scope.getGui().getStatus().endSubStatus("Generating " + targetPopulation + " agents");
        return true;

    }

    private Object getAttributeValue(final IScope scope, final APopulationEntity entity, final APopulationAttribute attribute) {
        final IType<?> type = getAttributeType(attribute);
        final APopulationValue value = entity.getValueForAttribute(attribute);
        if (value instanceof UniqueValue) {
            return type.cast(scope, value.getStringValue(), null, false);
        } else if (value instanceof RangeValue) {
            return drawValue(scope, type, (RangeValue) value);
        } else
            return value.getStringValue();

    }

    private Object drawValue(final IScope scope, final IType<?> type, final RangeValue value) {
        switch (type.id()) {
            case IType.INT: {
                final int lower = Cast.asInt(scope, value.getInputStringLowerBound());
                final int upper = Cast.asInt(scope, value.getInputStringUpperBound());
                return scope.getRandom().between(lower, upper);
            }
            case IType.FLOAT: {
                final double lower = Cast.asFloat(scope, value.getInputStringLowerBound());
                final double upper = Cast.asFloat(scope, value.getInputStringUpperBound());
                return scope.getRandom().between(lower, upper);
            }
            default:
                return null;
        }
    }

    private IType<?> getAttributeType(final APopulationAttribute attribute) {
        final GSEnumDataType gsType = attribute.getDataType();
        switch (gsType) {
            case Boolean:
                return Types.BOOL;
            case String:
                return Types.STRING;
            case Integer:
                return Types.INT;
            case Double:
                return Types.FLOAT;
        }
        return Types.STRING;
    }

    @Override
    public IType<?> fromFacetType() {
        return Types.FILE;
    }

}