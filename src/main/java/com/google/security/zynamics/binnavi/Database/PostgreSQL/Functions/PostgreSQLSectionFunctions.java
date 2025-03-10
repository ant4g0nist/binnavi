/*
Copyright 2015 Google Inc. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.google.security.zynamics.binnavi.Database.PostgreSQL.Functions;

import java.math.BigInteger;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.security.zynamics.binnavi.Database.CConnection;
import com.google.security.zynamics.binnavi.Database.Exceptions.CouldntDeleteException;
import com.google.security.zynamics.binnavi.Database.Exceptions.CouldntLoadDataException;
import com.google.security.zynamics.binnavi.Database.Exceptions.CouldntSaveDataException;
import com.google.security.zynamics.binnavi.Database.Interfaces.SQLProvider;
import com.google.security.zynamics.binnavi.disassembly.CommentManager;
import com.google.security.zynamics.binnavi.disassembly.INaviModule;
import com.google.security.zynamics.binnavi.disassembly.types.Section;
import com.google.security.zynamics.binnavi.disassembly.types.SectionPermission;
import com.google.security.zynamics.zylib.disassembly.CAddress;
import com.google.security.zynamics.zylib.disassembly.IAddress;

/**
 * Contains functions to work with section objects in the database.
 */
public class PostgreSQLSectionFunctions {

  /**
   * This function creates a new {@link Section} in the database.
   * 
   * @param connection The {@link Connection} to access the database with.
   * @param moduleId The id of the module in which to create the section.
   * @param name The name of the section.
   * @param commentId The id of the comment associated with the section.
   * @param startAddress The start address of the section.
   * @param endAddress The end address of the section.
   * @param permission The {@link SectionPermission} of the section.
   * @param data The data of the section.
   * 
   * @return The id of the section generated by the database.
   * 
   * @throws CouldntSaveDataException if the section could not be created in the database.
   */
  public static int createSection(final Connection connection, final int moduleId,
      final String name, final Integer commentId, final BigInteger startAddress,
      final BigInteger endAddress, final SectionPermission permission, final byte[] data)
      throws CouldntSaveDataException {

    Preconditions.checkNotNull(connection, "Error: connection argument can not be null");
    Preconditions.checkArgument(moduleId > 0, "Error: module id must be greater then zero");
    Preconditions.checkNotNull(name, "Error: name argument can not be null");
    Preconditions.checkNotNull(startAddress, "Error: startAddress argument can not be null");
    Preconditions.checkNotNull(endAddress, "Error: endAddress argument can not be null");
    Preconditions.checkNotNull(permission, "Error: permission argument can not be null");

    final String query = " { ? = call create_section( ?, ?, ?, ?, ?, ?, ?) } ";
    try (CallableStatement createSectionProcedure = connection.prepareCall(query)) {

        createSectionProcedure.registerOutParameter(1, Types.INTEGER);
        createSectionProcedure.setInt(2, moduleId);
        createSectionProcedure.setString(3, name);
        if (commentId == null) {
          createSectionProcedure.setNull(4, Types.INTEGER);
        } else {
          createSectionProcedure.setInt(4, commentId);
        }
        createSectionProcedure.setObject(5, startAddress, Types.BIGINT);
        createSectionProcedure.setObject(6, endAddress, Types.BIGINT);
        createSectionProcedure.setObject(7, permission.name(), Types.OTHER);
        createSectionProcedure.setBytes(8, data);
        createSectionProcedure.execute();

        final int sectionId = createSectionProcedure.getInt(1);
        if (createSectionProcedure.wasNull()) {
          throw new CouldntSaveDataException("Error: Got a section id of null from the database.");
        }
        return sectionId;
 
    } catch (final SQLException exception) {
      throw new CouldntSaveDataException(exception);
    }
  }

  /**
   * Deletes a section from the database.
   * 
   * @param provider The {@link SQLProvider} used to access the database.
   * @param section The {@link Section} that will be deleted.
   * 
   * @throws CouldntLoadDataException if the {@link Section} could not be deleted from the database.
   */
  public static void deleteSection(final SQLProvider provider, final Section section)
      throws CouldntLoadDataException {

    Preconditions.checkNotNull(provider, "Error: provider argument can not be null");
    Preconditions.checkNotNull(section, "Error: section argument can not be null");

    final String query = " { call delete_section(?, ?) } ";

    try (CallableStatement procedure =
          provider.getConnection().getConnection().prepareCall(query)) {
     
      procedure.setInt(1, section.getModule().getConfiguration().getId());
      procedure.setInt(2, section.getId());
    
      procedure.execute();
      
    } catch (final SQLException exception) {
      throw new CouldntLoadDataException(exception);
    }

  }

  /**
   * Loads all sections that are associated with the given module.
   * 
   * @param provider The SQL provider that holds the database connection.
   * @param module The module whose sections should be loaded.
   * @return The list of sections loaded from the database.
   * @throws CouldntLoadDataException Thrown if the sections could not be loaded from the database.
   */
  public static Map<Section, Integer> loadSections(final SQLProvider provider,
      final INaviModule module) throws CouldntLoadDataException {

    Preconditions.checkNotNull(provider, "Error: provider argument can not be null");
    Preconditions.checkNotNull(module, "Error: module argument can not be null");

    final HashMap<Section, Integer> sections = Maps.newHashMap();

    final String query = "SELECT * FROM get_sections(?)";
    try (PreparedStatement statement =
          provider.getConnection().getConnection().prepareStatement(query)) {

        statement.setInt(1, module.getConfiguration().getId());
        final ResultSet result = statement.executeQuery();
        while (result.next()) {
          final int id = result.getInt("id");
          final String name = result.getString("name");
          Integer commentId = result.getInt("comment_id");
          if (result.wasNull()) {
            commentId = null;
          }
          final IAddress startAddress = new CAddress(result.getLong("start_address"));
          final IAddress endAddress = new CAddress(result.getLong("end_address"));
          final SectionPermission permission =
              SectionPermission.valueOf(result.getString("permission"));
          final byte[] data = result.getBytes("data");
          sections.put(new Section(id, name, CommentManager.get(provider), module, startAddress,
              endAddress, permission, data), commentId);
        }
      
    } catch (final SQLException exception) {
      throw new CouldntLoadDataException(exception);
    }
    
    return sections;
  }

  /**
   * Sets the name of the given section.
   * 
   * @param connection The connection to the database.
   * @param moduleId The id of the module that contains the section.
   * @param sectionId The id of the section.
   * @param name The new name for the section.
   * @throws CouldntSaveDataException Thrown if the name could not be written to the database.
   */
  public static void setSectionName(final Connection connection, final int moduleId,
      final int sectionId, final String name) throws CouldntSaveDataException {

    Preconditions.checkNotNull(connection, "Error: connection argument can not be null");
    Preconditions.checkArgument(moduleId > 0, "Error: module id must be greater than zero");
    Preconditions.checkArgument(sectionId >= 0,
        "Error: section id must be greater or equal than zero");
    Preconditions.checkNotNull(name, "Error: name argument can not be null");

    final String query = " { call set_section_name(?, ?, ?) } ";
    try (CallableStatement procedure = connection.prepareCall(query)) {
     
        procedure.setInt(1, moduleId);
        procedure.setInt(2, sectionId);
        procedure.setString(3, name);
        procedure.execute();
     
    } catch (final SQLException exception) {
      throw new CouldntSaveDataException(exception);
    }
  }

  /**
   * This function appends a section comment to the list of section comments associated to a section
   * in the database.
   * 
   * @param provider The provider used to access the database.
   * @param moduleId The id of the module to which the section is associated.
   * @param sectionId The id of the section to which the comment is associated.
   * @param commentText The text of the comment.
   * @param userId The id of the currently active user.
   * 
   * @return The id of the comment generated by the database.
   * 
   * @throws CouldntSaveDataException if the comment could not be saved in the database.
   */
  public static Integer appendSectionComment(final SQLProvider provider, final int moduleId,
      final int sectionId, final String commentText, final Integer userId)
      throws CouldntSaveDataException {

    Preconditions.checkArgument(moduleId > 0, "Error: module id must be greater then zero");
    Preconditions.checkArgument(sectionId >= 0,
        "Error: section id must be greater or equal than zero");
    Preconditions.checkNotNull(commentText, "Error: comment text argument can not be null");
    Preconditions.checkNotNull(userId, "Error: user id argument can not be null");

    final CConnection connection = provider.getConnection();

    final String function = " { ? = call append_section_comment(?, ?, ?, ?) } ";

    try (CallableStatement appendCommentFunction =
          connection.getConnection().prepareCall(function)) {
      
        appendCommentFunction.registerOutParameter(1, Types.INTEGER);
        appendCommentFunction.setInt(2, moduleId);
        appendCommentFunction.setInt(3, sectionId);
        appendCommentFunction.setInt(4, userId);
        appendCommentFunction.setString(5, commentText);
        appendCommentFunction.execute();

        final int commentId = appendCommentFunction.getInt(1);
        if (appendCommentFunction.wasNull()) {
          throw new CouldntSaveDataException("Error: Got an comment id of null from the database");
        }
        return commentId;

    } catch (final SQLException exception) {
      throw new CouldntSaveDataException(exception);
    }
  }

  /**
   * This function deletes a section comment associated with the given section from the database.
   * 
   * @param provider The provider used to access the database.
   * @param moduleId The id of the module to which the section is associated.
   * @param sectionId The id of the section to which the comment is associated.
   * @param commentId The id of the comment to get deleted.
   * @param userId The id of the currently active user.
   * 
   * @throws CouldntDeleteException if the comment could not be deleted from the database.
   */
  public static void deleteSectionComment(final SQLProvider provider, final int moduleId,
      final int sectionId, final Integer commentId, final Integer userId)
      throws CouldntDeleteException {

    Preconditions.checkArgument(moduleId > 0, "Error: module id must be greater then zero");
    Preconditions.checkArgument(sectionId >= 0,
        "Error: section id must be greater or equal than zero");
    Preconditions.checkNotNull(commentId, "Error: comment text argument can not be null");
    Preconditions.checkNotNull(userId, "Error: user id argument can not be null");

    final String function = " { ? = call delete_section_comment(?, ?, ?, ?) } ";

    try (CallableStatement deleteCommentStatement =
          provider.getConnection().getConnection().prepareCall(function)) {

        deleteCommentStatement.registerOutParameter(1, Types.INTEGER);
        deleteCommentStatement.setInt(2, moduleId);
        deleteCommentStatement.setInt(3, sectionId);
        deleteCommentStatement.setInt(4, commentId);
        deleteCommentStatement.setInt(5, userId);
        deleteCommentStatement.execute();
        deleteCommentStatement.getInt(1);
        if (deleteCommentStatement.wasNull()) {
          throw new IllegalArgumentException(
              "Error: The comment id returned from the database was null.");
        }

    } catch (final SQLException exception) {
      throw new CouldntDeleteException(exception);
    }
  }

  /**
   * This function edits a section comment.
   * 
   * @param provider The provider used to access the database.
   * @param moduleId The module id to which the section is associated to.
   * @param commentId The id of the comment associated to the section which is edited.
   * @param userId The id of the user currently active.
   * @param commentText The new text of the comment.
   * 
   * @throws CouldntSaveDataException if the comment could not be edited in the database.
   */
  public static void editSectionComment(final SQLProvider provider, final int moduleId,
      final Integer commentId, final Integer userId, final String commentText)
      throws CouldntSaveDataException {

    Preconditions.checkNotNull(provider, "Error: provider argument can not be null");
    Preconditions.checkArgument(moduleId > 0, "Error: module id must be greater then zero");
    Preconditions.checkNotNull(commentId, "Error: commentId argument can not be null");
    Preconditions.checkNotNull(userId, "Error: userId argument can not be null");
    Preconditions.checkNotNull(commentText, "Error: newComment argument can not be null");

    PostgreSQLCommentFunctions.editComment(provider, commentId, userId, commentText);
  }
}
