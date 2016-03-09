/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.factory;

import java.io.File;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.helpers.Service;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.kernel.DatabaseAvailability;
import org.neo4j.kernel.NeoStoreDataSource;
import org.neo4j.kernel.api.security.AuthManager;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.api.SchemaWriteGuard;
import org.neo4j.kernel.impl.api.index.RemoveOrphanConstraintIndexesOnStartup;
import org.neo4j.kernel.impl.constraints.ConstraintSemantics;
import org.neo4j.kernel.impl.constraints.StandardConstraintSemantics;
import org.neo4j.kernel.impl.core.DefaultLabelIdCreator;
import org.neo4j.kernel.impl.core.DefaultPropertyTokenCreator;
import org.neo4j.kernel.impl.core.DefaultRelationshipTypeCreator;
import org.neo4j.kernel.impl.core.DelegatingLabelTokenHolder;
import org.neo4j.kernel.impl.core.DelegatingPropertyKeyTokenHolder;
import org.neo4j.kernel.impl.core.DelegatingRelationshipTypeTokenHolder;
import org.neo4j.kernel.impl.core.ReadOnlyTokenCreator;
import org.neo4j.kernel.impl.core.TokenCreator;
import org.neo4j.kernel.impl.coreapi.CoreAPIAvailabilityGuard;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.ResourceTypes;
import org.neo4j.kernel.impl.locking.community.CommunityLockManger;
import org.neo4j.kernel.impl.logging.LogService;
import org.neo4j.kernel.impl.store.format.standard.StandardV3_0;
import org.neo4j.kernel.impl.store.id.DefaultIdGeneratorFactory;
import org.neo4j.kernel.impl.store.id.IdGeneratorFactory;
import org.neo4j.kernel.impl.transaction.TransactionHeaderInformationFactory;
import org.neo4j.kernel.impl.transaction.state.DataSourceManager;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.kernel.internal.KernelData;
import org.neo4j.kernel.internal.Version;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.kernel.lifecycle.LifecycleStatus;
import org.neo4j.udc.UsageData;

/**
 * This implementation of {@link org.neo4j.kernel.impl.factory.EditionModule} creates the implementations of services
 * that are specific to the Community edition.
 */
public class CommunityEditionModule extends EditionModule
{
    public CommunityEditionModule( PlatformModule platformModule )
    {
        org.neo4j.kernel.impl.util.Dependencies dependencies = platformModule.dependencies;
        Config config = platformModule.config;
        LogService logging = platformModule.logging;
        FileSystemAbstraction fileSystem = platformModule.fileSystem;
        PageCache pageCache = platformModule.pageCache;
        File storeDir = platformModule.storeDir;
        DataSourceManager dataSourceManager = platformModule.dataSourceManager;
        LifeSupport life = platformModule.life;
        life.add( platformModule.dataSourceManager );

        GraphDatabaseFacade graphDatabaseFacade = platformModule.graphDatabaseFacade;

        lockManager = dependencies.satisfyDependency( createLockManager( config, logging ) );

        idGeneratorFactory = dependencies.satisfyDependency( createIdGeneratorFactory( fileSystem ) );

        propertyKeyTokenHolder = life.add( dependencies.satisfyDependency( new DelegatingPropertyKeyTokenHolder(
                createPropertyKeyCreator( config, dataSourceManager, idGeneratorFactory ) ) ) );
        labelTokenHolder = life.add( dependencies.satisfyDependency(new DelegatingLabelTokenHolder( createLabelIdCreator( config,
                dataSourceManager, idGeneratorFactory ) ) ));
        relationshipTypeTokenHolder = life.add( dependencies.satisfyDependency(new DelegatingRelationshipTypeTokenHolder(
                createRelationshipTypeCreator( config, dataSourceManager, idGeneratorFactory ) ) ));

        dependencies.satisfyDependency(
                createKernelData( fileSystem, pageCache, storeDir, config, graphDatabaseFacade, life ) );

        life.add( dependencies.satisfyDependency( createAuthManager( config, logging ) ) );

        commitProcessFactory = new CommunityCommitProcessFactory();

        headerInformationFactory = createHeaderInformationFactory();

        schemaWriteGuard = createSchemaWriteGuard();

        transactionStartTimeout = config.get( GraphDatabaseSettings.transaction_start_timeout );

        constraintSemantics = createSchemaRuleVerifier();

        coreAPIAvailabilityGuard = new CoreAPIAvailabilityGuard( platformModule.availabilityGuard, transactionStartTimeout );

        ioLimiter = IOLimiter.unlimited();

        formats = StandardV3_0.RECORD_FORMATS;

        registerRecovery( platformModule.databaseInfo, life, dependencies );

        publishEditionInfo( dependencies.resolveDependency( UsageData.class ), platformModule.databaseInfo, config );
    }

    protected ConstraintSemantics createSchemaRuleVerifier()
    {
        return new StandardConstraintSemantics();
    }

    protected SchemaWriteGuard createSchemaWriteGuard()
    {
        return () -> {};
    }

    protected TokenCreator createRelationshipTypeCreator( Config config, DataSourceManager dataSourceManager,
                                                          IdGeneratorFactory idGeneratorFactory )
    {
        if ( config.get( GraphDatabaseSettings.read_only ) )
        {
            return new ReadOnlyTokenCreator();
        }
        else
        {
            return new DefaultRelationshipTypeCreator( dataSourceManager, idGeneratorFactory );
        }
    }

    protected TokenCreator createPropertyKeyCreator( Config config, DataSourceManager dataSourceManager,
                                                     IdGeneratorFactory idGeneratorFactory )
    {
        if ( config.get( GraphDatabaseSettings.read_only ) )
        {
            return new ReadOnlyTokenCreator();
        }
        else
        {
            return new DefaultPropertyTokenCreator( dataSourceManager, idGeneratorFactory );
        }
    }

    protected TokenCreator createLabelIdCreator( Config config, DataSourceManager dataSourceManager,
                                                 IdGeneratorFactory idGeneratorFactory )
    {
        if ( config.get( GraphDatabaseSettings.read_only ) )
        {
            return new ReadOnlyTokenCreator();
        }
        else
        {
            return new DefaultLabelIdCreator( dataSourceManager, idGeneratorFactory );
        }
    }

    protected KernelData createKernelData( FileSystemAbstraction fileSystem, PageCache pageCache, File storeDir,
            Config config, GraphDatabaseAPI graphAPI, LifeSupport life )
    {
        return life.add( new DefaultKernelData( fileSystem, pageCache, storeDir, config, graphAPI ) );
    }

    protected IdGeneratorFactory createIdGeneratorFactory( FileSystemAbstraction fs )
    {
        return new DefaultIdGeneratorFactory( fs );
    }

    public static Locks createLockManager( Config config, LogService logging )
    {
        String key = config.get( GraphDatabaseFacadeFactory.Configuration.lock_manager );
        for ( Locks.Factory candidate : Service.load( Locks.Factory.class ) )
        {
            String candidateId = candidate.getKeys().iterator().next();
            if ( candidateId.equals( key ) )
            {
                return candidate.newInstance( ResourceTypes.values() );
            }
            else if ( key.equals( "" ) )
            {
                logging.getInternalLog( CommunityFacadeFactory.class )
                        .info( "No locking implementation specified, defaulting to '" + candidateId + "'" );
                return candidate.newInstance( ResourceTypes.values() );
            }
        }

        if ( key.equals( "community" ) )
        {
            return new CommunityLockManger();
        }
        else if ( key.equals( "" ) )
        {
            logging.getInternalLog( CommunityFacadeFactory.class )
                    .info( "No locking implementation specified, defaulting to 'community'" );
            return new CommunityLockManger();
        }

        throw new IllegalArgumentException( "No lock manager found with the name '" + key + "'." );
    }

    public static AuthManager createAuthManager( Config config, LogService logging )
    {
        boolean authEnabled = config.get( GraphDatabaseSettings.auth_enabled );
        if ( !authEnabled )
        {
            return AuthManager.NO_AUTH;
        }

        String key = config.get( GraphDatabaseFacadeFactory.Configuration.auth_manager );
        for ( AuthManager.Factory candidate : Service.load( AuthManager.Factory.class ) )
        {
            String candidateId = candidate.getKeys().iterator().next();
            if ( candidateId.equals( key ) )
            {
                return candidate.newInstance( config, logging.getUserLogProvider() );
            }
            else if ( key.equals( "" ) )
            {
                logging.getInternalLog( CommunityFacadeFactory.class )
                        .info( "No auth manager implementation specified, defaulting to '" + candidateId + "'" );
                return candidate.newInstance( config, logging.getUserLogProvider() );
            }
        }

        // TODO: Maybe the system should be locked down so you have to have an auth manager configured to get access at all
        if ( key.equals( "" ) )
        {
            logging.getInternalLog( CommunityFacadeFactory.class )
                    .info( "No auth manager implementation specified, defaulting to no authentication" );
            return AuthManager.NO_AUTH;
        }

        throw new IllegalArgumentException( "No auth manager found with the name '" + key + "'." );
    }

    protected TransactionHeaderInformationFactory createHeaderInformationFactory()
    {
        return TransactionHeaderInformationFactory.DEFAULT;
    }

    protected void registerRecovery( final DatabaseInfo databaseInfo, LifeSupport life,
                                     final DependencyResolver dependencyResolver )
    {
        life.addLifecycleListener( ( instance, from, to ) -> {
            if ( instance instanceof DatabaseAvailability && to.equals( LifecycleStatus.STARTED ) )
            {
                doAfterRecoveryAndStartup( databaseInfo, dependencyResolver );
            }
        } );
    }

    @Override
    protected void doAfterRecoveryAndStartup( DatabaseInfo databaseInfo, DependencyResolver dependencyResolver )
    {
        super.doAfterRecoveryAndStartup( databaseInfo, dependencyResolver );

        new RemoveOrphanConstraintIndexesOnStartup( dependencyResolver.resolveDependency( NeoStoreDataSource.class )
                .getKernel(), dependencyResolver.resolveDependency( LogService.class ).getInternalLogProvider() ).perform();
    }

    protected final class DefaultKernelData extends KernelData implements Lifecycle
    {
        private final GraphDatabaseAPI graphDb;

        public DefaultKernelData( FileSystemAbstraction fileSystem, PageCache pageCache, File storeDir, Config config,
                GraphDatabaseAPI graphDb )
        {
            super( fileSystem, pageCache, storeDir, config );
            this.graphDb = graphDb;
        }

        @Override
        public Version version()
        {
            return Version.getKernel();
        }

        @Override
        public GraphDatabaseAPI graphDatabase()
        {
            return graphDb;
        }

        @Override
        public void init() throws Throwable
        {
        }

        @Override
        public void start() throws Throwable
        {
        }

        @Override
        public void stop() throws Throwable
        {
        }
    }
}
